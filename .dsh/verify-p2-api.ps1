# P2 API verification: rooms, conflict detection, and the two approval flows.
#
# Covers the P2 acceptance path end to end:
#   teacher submits a course application -> admin approves (a real course row appears)
#   -> teacher applies for a time slot -> conflicts are detected and reported
#   -> admin approve is HARD-BLOCKED on conflict -> a free room is recommended.
#
# It also asserts the negative side (anonymous/student/teacher denials), because the
# permission rules are first-match-wins and a misordered rule silently opens an endpoint.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI). Room names start
# with a Chinese character, so that one character is built from its code point instead
# of being written literally -- see $JIAO.
#
# Re-runnable: it wipes its own fixtures (course codes VERIFY-P2-*) before AND after.
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe'
$pass = 0; $fail = 0

# U+6559 = the leading character of every building name ("jiao" = teaching building)
$JIAO = [char]0x6559

$CODE_A = 'VERIFY-P2-01'
$CODE_B = 'VERIFY-P2-02'
$CODE_C = 'VERIFY-P2-03'
$TERM = '2024-2025-1'

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + "  -> " + $detail) -ForegroundColor Red }
}

function Login($u, $p) {
  try {
    $r = Invoke-RestMethod -Uri "$base/login" -Method POST -ContentType 'application/json' `
      -Body (@{ username = $u; password = $p } | ConvertTo-Json) -TimeoutSec 15
    return $r.token
  } catch { return $null }
}

function Cleanup {
  $sql = "delete from class_time where course_id in (select id from course where course_code like 'VERIFY-P2-%'); " +
         "delete from class_time_apply where course_id in (select id from course where course_code like 'VERIFY-P2-%'); " +
         "delete from course_apply where course_code like 'VERIFY-P2-%'; " +
         "delete from course where course_code like 'VERIFY-P2-%';"
  & $mysql -uroot -p123456 -D edujwxt -N -B -e $sql 2>&1 | Out-Null
}

function Api($method, $path, $token, $body) {
  $headers = @{}
  if ($token) { $headers['token'] = $token }
  $p = @{ Uri = ($base + $path); Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 25 }
  if ($null -ne $body) {
    #  charset=utf-8 is required: without it PowerShell encodes the body with the default
    # codepage and replaces non-ASCII with '?'. See docs/.md (P4 section).
    $p['ContentType'] = 'application/json; charset=utf-8'
    $p['Body'] = ($body | ConvertTo-Json -Depth 6)
  }
  $status = 0; $txt = ''
  try {
    $r = Invoke-WebRequest @p
    $status = [int]$r.StatusCode
    $txt = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
  } catch {
    try { $status = [int]$_.Exception.Response.StatusCode.value__ } catch { $status = 0 }
    try {
      $stream = $_.Exception.Response.GetResponseStream()
      $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
      $txt = $reader.ReadToEnd()
    } catch { $txt = $_.Exception.Message }
  }
  $json = $null
  try { $json = ConvertFrom-Json $txt } catch { }
  return @{
    status = $status
    code   = $(if ($json) { $json.code } else { 'n/a' })
    msg    = $(if ($json) { $json.msg } else { '' })
    data   = $(if ($json) { $json.data } else { $null })
    json   = $json
    body   = $txt
  }
}

function Brief($r) { return ("status=" + $r.status + " code=" + $r.code + " body=" + $r.body.Substring(0, [Math]::Min(150, $r.body.Length))) }

# Null-safe count of a maybe-scalar/maybe-array JSON value.
#
# Why a helper instead of `@($v).Count` everywhere: this returns a real Int32, so it is
# immune to the two traps that silently produced "rows=" and false failures --
#   1. PowerShell UNROLLS an array returned from a function, and
#   2. `.Count` on a bare PSCustomObject yields NOTHING (not 0, not 1) in this PS version.
# Everywhere a value is piped/filtered/indexed, use inline `@($v)` instead: that is a
# plain expression, so nothing unrolls.
function Cnt($v) { if ($null -eq $v) { return 0 } else { return @($v).Count } }

# ============================================================
Write-Host "`n=== 0. fixture cleanup + logins ===" -ForegroundColor Cyan
Cleanup

$admin = Login 'admin01' '123456'
$t1 = Login '10001' '123456'
$t2 = Login '10002' '123456'
$t3 = Login '10003' '123456'
$stu = Login '2023001' '123456'
Check 'admin01 login' ($null -ne $admin)
Check 'teacher 10001 login' ($null -ne $t1)
Check 'teacher 10002 login' ($null -ne $t2)
Check 'teacher 10003 login' ($null -ne $t3)
Check 'student 2023001 login' ($null -ne $stu)

# ============================================================
Write-Host "`n=== 1. anonymous must be rejected (401) ===" -ForegroundColor Cyan
$anonTargets = @(
  @('GET', '/room'), @('GET', '/room/free'), @('GET', '/class-time'), @('GET', '/class-time/my'),
  @('GET', '/course-apply'), @('GET', '/class-time/apply')
)
foreach ($x in $anonTargets) {
  $r = Api $x[0] $x[1] $null $null
  Check ("anon " + $x[0] + " " + $x[1] + " -> 401") ($r.status -eq 401) (Brief $r)
}
$r = Api 'POST' '/course-apply' $null @{ courseCode = $CODE_A; courseName = 'x'; term = $TERM; maxStudent = 10; credit = 1; classHour = 16 }
Check 'anon POST /course-apply -> 401' ($r.status -eq 401) (Brief $r)
$r = Api 'POST' '/class-time/apply' $null @{ courseId = 1; weekday = 1; startPeriod = 1; endPeriod = 2; startWeek = 1; endWeek = 16 }
Check 'anon POST /class-time/apply -> 401' ($r.status -eq 401) (Brief $r)

# ============================================================
Write-Host "`n=== 2. student must NOT reach any scheduling endpoint (403) ===" -ForegroundColor Cyan
foreach ($x in $anonTargets) {
  $r = Api $x[0] $x[1] $stu $null
  Check ("student " + $x[0] + " " + $x[1] + " -> 403") ($r.status -eq 403) (Brief $r)
}
$r = Api 'POST' '/course-apply' $stu @{ courseCode = $CODE_A; courseName = 'x'; term = $TERM; maxStudent = 10; credit = 1; classHour = 16 }
Check 'student POST /course-apply -> 403' ($r.status -eq 403) (Brief $r)

# ============================================================
Write-Host "`n=== 3. teacher is allowed the read/apply surface, denied the admin surface ===" -ForegroundColor Cyan
$teacherAllowed = @(
  # NOTE: the concatenation must be parenthesised -- in PowerShell the comma operator
  # binds tighter than '+', so without parens this would silently become 3 array elements.
  @('GET', ('/room/free?term=' + $TERM + '&weekday=1&startPeriod=3&endPeriod=4&startWeek=1&endWeek=16')),
  @('GET', '/class-time/my'),
  @('GET', '/class-time/course/1'),
  @('GET', '/class-time/apply/my'),
  @('GET', '/course-apply/my')
)
foreach ($x in $teacherAllowed) {
  $r = Api $x[0] $x[1] $t1 $null
  Check ("teacher " + $x[1] + " -> 200") ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
}
$teacherDenied = @(
  @('GET', '/room'), @('GET', '/class-time'), @('GET', '/course-apply'),
  @('GET', '/class-time/apply'), @('DELETE', '/class-time/1'),
  @('POST', '/course-apply/1/approve'), @('POST', '/course-apply/1/reject'),
  @('POST', '/class-time/apply/1/approve'), @('POST', '/class-time/apply/1/reject')
)
foreach ($x in $teacherDenied) {
  $r = Api $x[0] $x[1] $t1 $null
  Check ("teacher " + $x[0] + " " + $x[1] + " -> 403") ($r.status -eq 403) (Brief $r)
}
$r = Api 'POST' '/class-time/check' $t1 @{ courseId = 1; weekday = 1; startPeriod = 1; endPeriod = 2; startWeek = 1; endWeek = 16 }
Check 'teacher POST /class-time/check -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)

# ============================================================
Write-Host "`n=== 4. room data (800 rooms generated by the migration) ===" -ForegroundColor Cyan
$r = Api 'GET' '/room?pageNum=1&pageSize=1' $admin $null
Check 'admin GET /room -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'room total is 800' ([int]$r.data.total -eq 800) ("total=" + $r.data.total)

# Fetch every room in one page and filter locally: putting a Chinese building name in the
# query string would depend on URL encoding, which is not what this script is testing.
$r = Api 'GET' '/room?pageNum=1&pageSize=900' $admin $null
$allRooms = @($r.data.list)
Check 'one page returns all 800 rooms' ($allRooms.Count -eq 800) ("rows=" + $allRooms.Count)
$building1 = $JIAO.ToString() + '1'
Check 'building 1 has 100 rooms' (@($allRooms | Where-Object { $_.building -eq $building1 }).Count -eq 100) ("count=" + @($allRooms | Where-Object { $_.building -eq $building1 }).Count)
Check 'capacity>=240 rooms are the 80 top-floor ones' (@($allRooms | Where-Object { [int]$_.capacity -ge 240 }).Count -eq 80) ("count=" + @($allRooms | Where-Object { [int]$_.capacity -ge 240 }).Count)
Check 'every room has a non-empty display name' (@($allRooms | Where-Object { [string]::IsNullOrWhiteSpace($_.roomName) }).Count -eq 0) 'blank room name found'

$room101 = ($allRooms | Where-Object { $_.roomName -eq ($JIAO.ToString() + '1-101') })[0]
$room102 = ($allRooms | Where-Object { $_.roomName -eq ($JIAO.ToString() + '1-102') })[0]
Check 'room 101 exists' ($null -ne $room101) 'not found'
Check 'room 101 capacity is 60' ($null -ne $room101 -and [int]$room101.capacity -eq 60) ("cap=" + $room101.capacity)
Check 'room 102 exists' ($null -ne $room102) 'not found'
Check 'room 101 is type NORMAL' ($null -ne $room101 -and $room101.roomType -eq 'NORMAL') ("type=" + $room101.roomType)

$r = Api 'GET' '/room?minCapacity=240&pageSize=1' $admin $null
Check 'minCapacity filter narrows the total' ([int]$r.data.total -eq 80) ("total=" + $r.data.total)
$r = Api 'GET' ("/room/" + $room101.id) $admin $null
Check 'admin GET /room/{id} -> 200' ($r.status -eq 200 -and $r.data.roomName -eq $room101.roomName) (Brief $r)

# ============================================================
Write-Host "`n=== 5. seeded timetable + conflict detection ===" -ForegroundColor Cyan
$cs101 = $null
$r = Api 'GET' '/course?page=1&pageSize=100' $admin $null
foreach ($c in @($r.data.list)) { if ($c.courseCode -eq 'CS101') { $cs101 = $c } }
Check 'CS101 is visible with its course code (P1 gap fix)' ($null -ne $cs101) ("courseCode visible on " + (Cnt $r.data.list) + " rows")

$a = Api 'GET' ('/class-time/course/' + $cs101.id) $admin $null
Check 'CS101 has a seeded timetable row' ((Cnt $a.data) -eq 1) ("rows=" + (Cnt $a.data))
Check 'CS101 is Monday 1-2, weeks 1-16' (
  @($a.data)[0].weekday -eq 1 -and @($a.data)[0].startPeriod -eq 1 -and
  @($a.data)[0].endPeriod -eq 2 -and @($a.data)[0].startWeek -eq 1 -and @($a.data)[0].endWeek -eq 16
) (@($a.data)[0] | ConvertTo-Json -Compress)

# adjacent periods: must NOT conflict
$r = Api 'POST' '/class-time/check' $admin @{ courseId = $cs101.id; weekday = 1; startPeriod = 3; endPeriod = 4; startWeek = 1; endWeek = 16 }
Check 'adjacent 3-4 vs 1-2 -> no conflict' ($r.code -eq '200' -and $r.data.conflict -eq $false) (Brief $r)
Check 'adjacent case returns no teacher conflicts' ((Cnt $r.data.teacherConflicts) -eq 0) (Brief $r)

# overlapping periods: must conflict
$r = Api 'POST' '/class-time/check' $admin @{ courseId = $cs101.id; weekday = 1; startPeriod = 2; endPeriod = 3; startWeek = 1; endWeek = 16 }
Check 'overlapping 2-3 vs 1-2 -> conflict' ($r.data.conflict -eq $true) (Brief $r)
Check 'conflict names CS101' ((Cnt $r.data.teacherConflicts) -ge 1 -and @($r.data.teacherConflicts)[0].courseCode -eq 'CS101') (Brief $r)

# non-overlapping weeks: must NOT conflict
$r = Api 'POST' '/class-time/check' $admin @{ courseId = $cs101.id; weekday = 1; startPeriod = 1; endPeriod = 2; startWeek = 17; endWeek = 18 }
Check 'weeks 17-18 vs 1-16 -> no conflict' ($r.data.conflict -eq $false) (Brief $r)

# room dimension: room 1-102 is CS102's room on Wednesday 3-4
$r = Api 'POST' '/class-time/check' $admin @{ weekday = 3; startPeriod = 4; endPeriod = 5; startWeek = 1; endWeek = 16; roomId = $room102.id }
Check 'room conflict query needs term' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' ('/class-time/check?term=' + $TERM) $admin @{ weekday = 3; startPeriod = 4; endPeriod = 5; startWeek = 1; endWeek = 16; roomId = $room102.id }
Check 'room 102 Wednesday 4-5 -> room conflict' ($r.data.conflict -eq $true) (Brief $r)
Check 'room conflict is reported in the room list' ((Cnt $r.data.roomConflicts) -ge 1) (Brief $r)

$r = Api 'POST' ('/class-time/check?term=' + $TERM) $admin @{ weekday = 3; startPeriod = 1; endPeriod = 2; startWeek = 1; endWeek = 16; roomId = $room102.id }
Check 'room 102 Wednesday 1-2 -> no conflict' ($r.data.conflict -eq $false) (Brief $r)

# invalid slot
$r = Api 'POST' ('/class-time/check?term=' + $TERM) $admin @{ weekday = 1; startPeriod = 1; endPeriod = 11; startWeek = 1; endWeek = 16 }
Check 'endPeriod=11 is rejected' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 6. room recommendation ===" -ForegroundColor Cyan
$r = Api 'GET' ('/room/free?term=' + $TERM + '&weekday=1&startPeriod=1&endPeriod=2&startWeek=1&endWeek=16&minCapacity=60&limit=1') $admin $null
Check 'recommendation found for a busy slot' ($r.data.found -eq $true) (Brief $r)
Check 'occupied room 101 is skipped' ($r.data.roomName -eq ($JIAO + '1-102')) ("got=" + $r.data.roomName)

$r = Api 'GET' ('/room/free?term=' + $TERM + '&weekday=1&startPeriod=3&endPeriod=4&startWeek=1&endWeek=16&minCapacity=60&limit=1') $admin $null
Check 'free slot recommends the lowest-capacity room' ($r.data.roomName -eq ($JIAO + '1-101')) ("got=" + $r.data.roomName)

$r = Api 'GET' ('/room/free?term=' + $TERM + '&weekday=1&startPeriod=3&endPeriod=4&startWeek=1&endWeek=16&minCapacity=999&limit=5') $admin $null
Check 'capacity 999 -> nothing found' ($r.data.found -eq $false) (Brief $r)

$r = Api 'GET' ('/room/free?term=' + $TERM + '&weekday=1&startPeriod=3&endPeriod=4&startWeek=1&endWeek=16&minCapacity=100&limit=10') $admin $null
$caps = @($r.data.candidates) | ForEach-Object { [int]$_.capacity }
Check 'candidates respect the capacity floor' (@($caps | Where-Object { $_ -lt 100 }).Count -eq 0) ($caps -join ',')
$sorted = $true
for ($i = 1; $i -lt $caps.Count; $i++) { if ($caps[$i] -lt $caps[$i - 1]) { $sorted = $false } }
Check 'candidates are ordered by capacity ascending' $sorted ($caps -join ',')

$r = Api 'GET' ('/room/free?term=' + $TERM + '&weekday=1&startPeriod=3&endPeriod=4&startWeek=16&endWeek=1') $admin $null
Check 'illegal week range is rejected' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 7. course application: validation ===" -ForegroundColor Cyan
$applyA = @{ courseCode = $CODE_A; courseName = 'VERIFY P2 course A'; term = $TERM; credit = 3.0; classHour = 48; maxStudent = 45 }
$bad = $applyA.Clone(); $bad.Remove('courseCode')
$r = Api 'POST' '/course-apply' $t1 $bad
Check 'apply without courseCode -> business error' ($r.code -ne '200') (Brief $r)
$bad = $applyA.Clone(); $bad['courseCode'] = '   '
$r = Api 'POST' '/course-apply' $t1 $bad
Check 'apply with blank courseCode -> business error' ($r.code -ne '200') (Brief $r)
$bad = $applyA.Clone(); $bad.Remove('term')
$r = Api 'POST' '/course-apply' $t1 $bad
Check 'apply without term -> business error' ($r.code -ne '200') (Brief $r)
$bad = $applyA.Clone(); $bad['maxStudent'] = 0
$r = Api 'POST' '/course-apply' $t1 $bad
Check 'apply with maxStudent=0 -> business error' ($r.code -ne '200') (Brief $r)
$bad = $applyA.Clone(); $bad['credit'] = -1
$r = Api 'POST' '/course-apply' $t1 $bad
Check 'apply with negative credit -> business error' ($r.code -ne '200') (Brief $r)
$bad = $applyA.Clone(); $bad['expectedWeekday'] = 1; $bad['expectedStartPeriod'] = 5; $bad['expectedEndPeriod'] = 2; $bad['expectedStartWeek'] = 1; $bad['expectedEndWeek'] = 16
$r = Api 'POST' '/course-apply' $t1 $bad
Check 'apply with inverted expected periods -> business error' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 8. course application: submit -> approve -> course row created ===" -ForegroundColor Cyan
$r = Api 'POST' '/course-apply' $t1 $applyA
Check 'teacher submits course application -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'new application is PENDING' ($r.data.status -eq 'PENDING') ("status=" + $r.data.status)
Check 'application records the submitting teacher (from token, not body)' ($r.data.teacherId -eq '10001') ("teacher=" + $r.data.teacherId)
$applyAId = [int]$r.data.id

$r = Api 'POST' '/course-apply' $t1 $applyA
Check 'duplicate application is rejected' ($r.code -ne '200') (Brief $r)

$r = Api 'POST' '/course-apply' $t1 (@{ courseCode = $CODE_C; courseName = 'spoof attempt'; term = $TERM; credit = 1; classHour = 16; maxStudent = 20; teacherId = '10002' })
Check 'body teacherId is ignored in favour of the token' ($r.code -eq '200' -and $r.data.teacherId -eq '10001') ("teacherId=" + $r.data.teacherId + " code=" + $r.code)

$r = Api 'GET' '/course-apply?status=PENDING' $admin $null
$pendingIds = @($r.data) | ForEach-Object { [int]$_.id }
Check 'admin sees the pending application' ($pendingIds -contains $applyAId) ("ids=" + ($pendingIds -join ','))

$r = Api 'POST' ("/course-apply/$applyAId/approve") $admin $null
Check 'admin approves -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'approved application links the created course' ($null -ne $r.data.createdCourseId -and [int]$r.data.createdCourseId -gt 0) ("createdCourseId=" + $r.data.createdCourseId)
Check 'reviewer is recorded' ($r.data.reviewer -eq 'admin01') ("reviewer=" + $r.data.reviewer)
$courseAId = [int]$r.data.createdCourseId

$r = Api 'POST' ("/course-apply/$applyAId/approve") $admin $null
Check 're-approving is rejected (state machine)' ($r.code -ne '200') (Brief $r)

$r = Api 'GET' '/course?page=1&pageSize=100' $admin $null
$created = @($r.data.list) | Where-Object { $_.id -eq $courseAId }
Check 'the created course row is queryable' ($null -ne $created) ("id=" + $courseAId)
Check 'created course keeps the course code (P1 gap fix on the write path)' ($null -ne $created -and $created.courseCode -eq $CODE_A) ("code=" + $created.courseCode)
Check 'created course keeps credit / capacity / term' (
  $null -ne $created -and [decimal]$created.credit -eq 3.0 -and [int]$created.maxStudent -eq 45 -and $created.term -eq $TERM
) ($created | ConvertTo-Json -Compress)
Check 'created course is owned by the applicant' ($null -ne $created -and $created.teacherId -eq '10001') ("teacher=" + $created.teacherId)

# ============================================================
Write-Host "`n=== 9. class-time application: submit -> approve -> timetable row ===" -ForegroundColor Cyan
$slot = @{ courseId = $courseAId; weekday = 1; startPeriod = 3; endPeriod = 4; startWeek = 1; endWeek = 16 }
$r = Api 'POST' '/class-time/apply' $t1 $slot
Check 'teacher submits class-time application -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'class-time application is PENDING' ($r.data.status -eq 'PENDING') ("status=" + $r.data.status)
Check 'no conflict recorded for Monday 3-4 (adjacent to CS101)' ($null -eq $r.data.conflictInfo -or $r.data.conflictInfo -eq '') ("conflictInfo=" + $r.data.conflictInfo)
$ctApply1 = [int]$r.data.id

$r = Api 'POST' "/course-apply/999999/approve" $admin $null
Check 'approving an unknown application -> business error' ($r.code -ne '200') (Brief $r)

$r = Api 'POST' "/class-time/apply/$ctApply1/approve" $admin $null
Check 'admin approves class-time -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'class-time application is APPROVED' ($r.data.status -eq 'APPROVED') ("status=" + $r.data.status)
Check 'a room was auto-assigned' ($null -ne $r.data.roomId -and $r.data.roomName) ("room=" + $r.data.roomName)
Check 'auto-assigned room is the free lowest-capacity one' ($r.data.roomName -eq ($JIAO + '1-101')) ("room=" + $r.data.roomName)

$r = Api 'GET' ("/class-time/course/$courseAId") $admin $null
Check 'timetable row was created for the course' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))
Check 'timetable row carries the approved slot and room' (
  @($r.data)[0].weekday -eq 1 -and @($r.data)[0].startPeriod -eq 3 -and @($r.data)[0].endPeriod -eq 4 -and
  @($r.data)[0].startWeek -eq 1 -and @($r.data)[0].endWeek -eq 16 -and $null -ne @($r.data)[0].roomId
) (@($r.data)[0] | ConvertTo-Json -Compress)

$r = Api 'GET' '/class-time/my' $t1 $null
$mineCodes = @($r.data) | ForEach-Object { $_.courseCode }
Check 'teacher timetable now contains the new course' ($mineCodes -contains $CODE_A) ("codes=" + ($mineCodes -join ','))

# The auto-assigned room must be written back onto the application row: otherwise the
# timetable says "room X" while the application record still says "unassigned".
$r = Api 'GET' '/class-time/apply?status=APPROVED' $admin $null
$approved = @($r.data) | Where-Object { $_.id -eq $ctApply1 }
Check 'the approved application shows the assigned room' ($null -ne $approved -and $null -ne $approved.roomId) ("roomId=" + $approved.roomId)
Check 'the approved application names the room (join works)' ($null -ne $approved -and $approved.roomName) ("roomName=" + $approved.roomName)
Check 'the assigned room matches the timetable row' ($null -ne $approved -and $approved.roomName -eq ($JIAO.ToString() + '1-101')) ("roomName=" + $approved.roomName)

# ============================================================
Write-Host "`n=== 10. conflict is HARD-BLOCKED at approval time ===" -ForegroundColor Cyan
# Same teacher (10001) already teaches CS101 on Monday 1-2 -> this must conflict.
$r = Api 'POST' '/class-time/apply' $t1 @{ courseId = $courseAId; weekday = 1; startPeriod = 1; endPeriod = 2; startWeek = 1; endWeek = 16 }
Check 'submit-time detects the conflict with CS101' ($r.data.conflictInfo -and $r.data.conflictInfo.Length -gt 0) ("conflictInfo=" + $r.data.conflictInfo)
Check 'submit-time conflict is still allowed to be submitted' ($r.data.status -eq 'PENDING') ("status=" + $r.data.status)
$ctApply2 = [int]$r.data.id

$r = Api 'POST' "/class-time/apply/$ctApply2/approve" $admin $null
Check 'approval is blocked by the conflict' ($r.code -ne '200') (Brief $r)
Check 'the block message explains the conflict' ($r.msg -and $r.msg.Length -gt 0) ("msg=" + $r.msg)

$r = Api 'GET' ("/class-time/course/$courseAId") $admin $null
Check 'no extra timetable row was created by the blocked approval' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))

$r = Api 'GET' '/class-time/apply?status=PENDING' $admin $null
$blocked = @($r.data) | Where-Object { $_.id -eq $ctApply2 }
Check 'the blocked application is still PENDING' ($null -ne $blocked) ("ids=" + ((@($r.data) | ForEach-Object { $_.id }) -join ','))
Check 'the conflict detail was persisted on the row' ($null -ne $blocked -and $blocked.conflictInfo -and $blocked.conflictInfo.Length -gt 0) ("conflictInfo=" + $blocked.conflictInfo)

# Approving with an explicit occupied room must also be blocked.
$r = Api 'POST' "/class-time/apply/$ctApply2/approve?roomId=$($room101.id)" $admin $null
Check 'blocked even when an explicit room is given' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 11. a course cannot be double-booked against ITSELF ===" -ForegroundColor Cyan
# Monday 4-5 overlaps the course's own approved 3-4 row; excludeCourseId must NOT be used
# on the insert path, so this has to be caught.
$r = Api 'POST' '/class-time/apply' $t1 @{ courseId = $courseAId; weekday = 1; startPeriod = 4; endPeriod = 5; startWeek = 1; endWeek = 16 }
$ctApply3 = [int]$r.data.id
$r = Api 'POST' "/class-time/apply/$ctApply3/approve" $admin $null
Check 'overlapping its own row is blocked' ($r.code -ne '200') (Brief $r)
$r = Api 'GET' ("/class-time/course/$courseAId") $admin $null
Check 'still exactly one timetable row' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))

# ============================================================
Write-Host "`n=== 12. room dimension blocks approval (different teacher, occupied room) ===" -ForegroundColor Cyan
# CS102 is taught by 10002 in the SAME term as CS101, and 10002 has no Monday 1-2 class,
# so only the room (bldg1-101, held by CS101) can conflict here. The term matters: conflicts
# are term-scoped, so picking a course from another term would test nothing.
$r = Api 'GET' '/course?page=1&pageSize=100' $admin $null
$cs102 = @($r.data.list) | Where-Object { $_.courseCode -eq 'CS102' }
Check 'CS102 course row found for the room-conflict case' ($null -ne $cs102) 'CS102 missing'
$r = Api 'POST' '/class-time/apply' $t2 @{ courseId = $cs102.id; weekday = 1; startPeriod = 1; endPeriod = 2; startWeek = 1; endWeek = 16; roomId = $room101.id }
Check 'room-only conflict is detected at submit time' ($r.status -eq 200 -and $r.data.conflictInfo -and $r.data.conflictInfo.Length -gt 0) (Brief $r)
Check 'no teacher conflict for 10002 at Monday 1-2' ($r.code -eq '200') (Brief $r)
$ctApply4 = [int]$r.data.id
$r = Api 'POST' "/class-time/apply/$ctApply4/approve" $admin $null
Check 'approval blocked by the occupied room (teacher had no conflict)' ($r.code -ne '200') (Brief $r)
$r = Api 'GET' ("/class-time/course/" + $cs102.id) $admin $null
# CS102 already has its own seeded Wednesday 3-4 row, so "unchanged" means still exactly
# that one row -- the blocked Monday 1-2 approval must not have added a second.
Check 'CS102 still has only its seeded timetable row' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))
Check 'the blocked approval added no Monday slot to CS102' (@($r.data | Where-Object { $_.weekday -eq 1 }).Count -eq 0) (@($r.data) | ConvertTo-Json -Compress)

# ============================================================
Write-Host "`n=== 13. ownership and rejection rules ===" -ForegroundColor Cyan
$r = Api 'POST' '/class-time/apply' $t2 @{ courseId = $courseAId; weekday = 2; startPeriod = 7; endPeriod = 8; startWeek = 1; endWeek = 16 }
Check 'another teacher cannot schedule someone elses course' ($r.code -ne '200') (Brief $r)

$r = Api 'POST' "/class-time/apply/$ctApply2/reject" $admin @{ }
Check 'reject without a reason is refused' ($r.code -ne '200') (Brief $r)

$r = Api 'POST' "/class-time/apply/$ctApply2/reject" $admin @{ reason = 'VERIFY: conflicting slot' }
Check 'reject with a reason -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'GET' '/class-time/apply?status=REJECTED' $admin $null
$rejected = @($r.data) | Where-Object { $_.id -eq $ctApply2 }
Check 'the application is REJECTED' ($null -ne $rejected) 'not found in REJECTED list'
Check 'the rejection reason is stored' ($null -ne $rejected -and $rejected.rejectReason -eq 'VERIFY: conflicting slot') ("reason=" + $rejected.rejectReason)

$r = Api 'POST' "/course-apply/$applyAId/reject" $admin @{ reason = 'VERIFY: already approved' }
Check 'rejecting an approved application is refused' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 14. second application + rejection path on course_apply ===" -ForegroundColor Cyan
$r = Api 'POST' '/course-apply' $t2 @{ courseCode = $CODE_B; courseName = 'VERIFY P2 course B'; term = $TERM; credit = 2.0; classHour = 32; maxStudent = 30 }
Check 'teacher 10002 submits an application' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$applyBId = [int]$r.data.id
$r = Api 'GET' ("/course-apply/$applyBId") $t2 $null
Check 'teacher can read own application detail' ($r.status -eq 200 -and [int]$r.data.id -eq $applyBId) (Brief $r)
$r = Api 'GET' ("/course-apply/$applyBId") $t1 $null
Check 'another teacher cannot read it (ownership check)' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' "/course-apply/$applyBId/reject" $admin @{ }
Check 'rejecting without a reason is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' "/course-apply/$applyBId/reject" $admin @{ reason = 'VERIFY: not needed this term' }
Check 'rejecting with a reason -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'POST' "/course-apply/$applyBId/approve" $admin $null
Check 'rejected application cannot be approved afterwards' ($r.code -ne '200') (Brief $r)
$r = Api 'GET' '/course?page=1&pageSize=100' $admin $null
$codeB = @($r.data.list) | Where-Object { $_.courseCode -eq $CODE_B }
Check 'the rejected application created no course row' ($null -eq $codeB) 'course row exists'

# ============================================================
Write-Host "`n=== 15. admin can read everything, teacher cannot see all ===" -ForegroundColor Cyan
$r = Api 'GET' '/class-time' $admin $null
Check 'admin GET /class-time -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'admin sees the seeded timetable plus the new row' ((Cnt $r.data) -ge 4) ("rows=" + (Cnt $r.data))
$r = Api 'GET' ('/class-time?term=' + $TERM) $admin $null
Check 'filtering by term works' ((Cnt $r.data) -ge 3) ("rows=" + (Cnt $r.data))

# ============================================================
Write-Host "`n=== 16. cleanup ===" -ForegroundColor Cyan
Cleanup
$r = Api 'GET' '/course?page=1&pageSize=200' $admin $null
$leftover = @($r.data.list) | Where-Object { $_.courseCode -like 'VERIFY-P2-*' }
Check 'fixture courses removed' ((Cnt $leftover) -eq 0) ("leftover=" + (Cnt $leftover))
$r = Api 'GET' ('/class-time/course/' + $cs101.id) $admin $null
Check 'seeded CS101 timetable untouched' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/course?page=1&pageSize=100' $admin $null
$real = @($r.data.list) | Where-Object { $_.courseCode -eq 'CS101' }
Check 'seeded CS101 course row untouched' ($null -ne $real) 'CS101 missing'

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
