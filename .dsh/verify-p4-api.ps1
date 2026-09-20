# P4 API verification: exam scheduling, the student "my exams" view, and exam conflicts.
#
# The centrepiece is the HALF-OPEN interval rule: exams are continuous clock intervals, so
#   "one exam ends at 11:00, the next starts at 11:00" is NOT a conflict,
# unlike P2's period slots where 3-4 and 4-5 DO share period 4 (closed interval).
# Getting these two mixed up is the most likely way to break exam scheduling.
#
# Fixtures live on courses that have no seeded exam (CS105/CS106) and are wiped before AND
# after the run, so the script is re-runnable and never disturbs the seeded exams.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI). Chinese message
# fragments are built from code points -- see the $S_* constants.
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe'
$pass = 0; $fail = 0

$TERM = '2024-2025-1'
$FIX_MARK = 'VERIFY-P4'

# --- Chinese fragments, built from code points so this file stays ASCII-only ---
$S_FINAL = "$([char]0x671F)$([char]0x672B)"                                                    # 
$S_MAKEUP = "$([char]0x8865)$([char]0x8003)"                                                   # 
$S_CLASH = "$([char]0x51B2)$([char]0x7A81)"                                                    # 
$S_EXISTS = "$([char]0x5DF2)$([char]0x6709)$([char]0x751F)$([char]0x6548)$([char]0x4E2D)"       # 
$S_NOTFOUND = "$([char]0x4E0D)$([char]0x5B58)$([char]0x5728)"                                  # 
# CJK used as TEST DATA (proves non-ASCII survives the request round trip).
$CN_AREA = [char]0x533A                                # CJK 'district/area'
$CN_ZHAO = "$([char]0x8D75)$([char]0x516D)"            # a person name
$CN_SUN  = "$([char]0x5B59)$([char]0x4E03)"            # another person name

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
  $sql = "delete from exam_schedule where remark = '$FIX_MARK'; " +
         "delete es from exam_schedule es join course c on es.course_id = c.id " +
         "where c.course_code in ('CS105','CS106') and c.term = '2024-2025-2';"
  & $mysql -uroot -p123456 -D edujwxt -N -B -e $sql 2>&1 | Out-Null
}

function Api($method, $path, $token, $body) {
  $headers = @{}
  if ($token) { $headers['token'] = $token }
  $p = @{ Uri = ($base + $path); Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 25 }
  if ($null -ne $body) {
    # ContentType MUST carry charset=utf-8: without it PowerShell encodes the body with the
    # default codepage and non-ASCII becomes '?' (measured: "D<CJK>01-20" arrived as "D??1-20").
    # Keep this whole file ASCII-only. It is executed by Windows PowerShell 5.1, which decodes
    # .ps1 as ANSI/GBK when there is no BOM -- a mis-decoded CJK comment swallowed the next
    # line of code (the ContentType assignment below!) and every POST silently became
    # form-urlencoded, which Spring's firewall then rejected.
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
    body   = $txt
  }
}

function Brief($r) { return ("status=" + $r.status + " code=" + $r.code + " body=" + $r.body.Substring(0, [Math]::Min(170, $r.body.Length))) }
function Cnt($v) { if ($null -eq $v) { return 0 } else { return @($v).Count } }
function Iso([datetime]$d) { return $d.ToString('yyyy-MM-ddTHH:mm:ss') }
function ParseIso($s) { return [datetime]::Parse($s.Replace('T',' ')) }

function CourseIdByCode($code, $admin) {
  $r = Api 'GET' '/course?page=1&pageSize=300' $admin $null
  $hit = @($r.data.list) | Where-Object { $_.courseCode -eq $code }
  if ((Cnt $hit) -eq 0) { return 0 }
  return [int]$hit[0].id
}

# ============================================================
Write-Host "`n=== 0. fixture cleanup + logins ===" -ForegroundColor Cyan
Cleanup

$admin = Login 'admin01' '123456'
$stu1 = Login '2023001' '123456'
$stu2 = Login '2023002' '123456'
$teacher = Login '10001' '123456'
Check 'admin01 login' ($null -ne $admin)
Check 'student 2023001 login' ($null -ne $stu1)
Check 'student 2023002 login' ($null -ne $stu2)
Check 'teacher 10001 login' ($null -ne $teacher)

# ============================================================
Write-Host "`n=== 1. permissions ===" -ForegroundColor Cyan
$adminPaths = @(
  @('GET', '/exam'), @('GET', '/exam/1'), @('GET', '/exam/course/1')
)
foreach ($x in $adminPaths) {
  $r = Api $x[0] $x[1] $null $null
  Check ("anon " + $x[1] + " -> 401") ($r.status -eq 401) (Brief $r)
}
$r = Api 'GET' '/exam/my' $null $null
Check 'anon GET /exam/my -> 401' ($r.status -eq 401) (Brief $r)
$r = Api 'POST' '/exam' $null @{ courseId = 1 }
Check 'anon POST /exam -> 401' ($r.status -eq 401) (Brief $r)

# student: may see own exams, must not manage schedules
$r = Api 'GET' '/exam/my' $stu1 $null
Check 'student GET /exam/my -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$stuDenied = @(
  @('GET', '/exam'), @('GET', '/exam/1'), @('GET', '/exam/course/1'), @('DELETE', '/exam/1')
)
foreach ($x in $stuDenied) {
  $r = Api $x[0] $x[1] $stu1 $null
  Check ("student " + $x[1] + " -> 403") ($r.status -eq 403) (Brief $r)
}
$r = Api 'POST' '/exam' $stu1 @{ courseId = 1; examType = 'FINAL' }
Check 'student POST /exam -> 403' ($r.status -eq 403) (Brief $r)
$r = Api 'PUT' '/exam' $stu1 @{ id = 1 }
Check 'student PUT /exam -> 403' ($r.status -eq 403) (Brief $r)
$r = Api 'POST' '/exam/check' $stu1 @{ courseId = 1; durationMinutes = 120 }
Check 'student POST /exam/check -> 403' ($r.status -eq 403) (Brief $r)

# teacher: no exam features at all
$t1 = Api 'GET' '/exam/my' $teacher $null
Check 'teacher GET /exam/my -> 403' ($t1.status -eq 403) (Brief $t1)
$t2 = Api 'GET' '/exam' $teacher $null
Check 'teacher GET /exam -> 403' ($t2.status -eq 403) (Brief $t2)
$t3 = Api 'POST' '/exam/check' $teacher @{ courseId = 1; durationMinutes = 120 }
Check 'teacher POST /exam/check -> 403' ($t3.status -eq 403) (Brief $t3)

$r = Api 'GET' '/exam' $admin $null
Check 'admin GET /exam -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)

# ============================================================
Write-Host "`n=== 2. student view: my exams ===" -ForegroundColor Cyan
$r = Api 'GET' '/exam/my' $stu1 $null
$mine = @($r.data)
Check 'student sees the four seeded exams' ((Cnt $mine) -eq 4) ("rows=" + (Cnt $mine))
Check 'results are ordered by exam time' (
  $mine.Count -ge 2 -and ((ParseIso $mine[0].examTime) -le (ParseIso $mine[1].examTime))
) (($mine | ForEach-Object { $_.examTime }) -join ' | ')
Check 'the earliest row is the past MAKEUP' ($mine[0].examType -eq 'MAKEUP' -and $mine[0].courseCode -eq 'CS104') ($mine[0] | ConvertTo-Json -Compress)
Check 'rows carry display fields the UI needs' (
  $null -ne $mine[0].courseCode -and $null -ne $mine[0].courseName -and $null -ne $mine[0].roomName -and $null -ne $mine[0].term
) ($mine[0] | ConvertTo-Json -Compress)
Check 'rows carry the Chinese type label' ($mine[0].typeLabel -eq $S_MAKEUP) ("typeLabel=" + $mine[0].typeLabel)
Check 'the FINAL rows say ' (@($mine | Where-Object { $_.examType -eq 'FINAL' })[0].typeLabel -eq $S_FINAL) 'typeLabel mismatch'
Check 'duration and seat range survived' ($mine[0].durationMinutes -eq 90 -and $mine[0].seatRange) ($mine[0] | ConvertTo-Json -Compress)

$r = Api 'GET' '/exam/my?upcoming=true' $stu1 $null
$upcoming = @($r.data)
Check 'upcoming=true hides the past exam' ((Cnt $upcoming) -eq 3) ("rows=" + (Cnt $upcoming))
Check 'nothing in the upcoming list is in the past' (
  @($upcoming | Where-Object { (ParseIso $_.examTime) -lt (Get-Date) }).Count -eq 0
) (($upcoming | ForEach-Object { $_.examTime }) -join ' | ')

$r = Api 'GET' "/exam/my?term=$TERM" $stu1 $null
Check 'term filter keeps all four' ((Cnt $r.data) -eq 4) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/exam/my?term=2033-2034' $stu1 $null
Check 'unknown term yields nothing' ((Cnt $r.data) -eq 0) ("rows=" + (Cnt $r.data))

$r1 = Api 'GET' '/exam/my' $stu1 $null
$r2 = Api 'GET' '/exam/my' $stu2 $null
Check 'two students are not given the same list' ($r1.body -ne $r2.body) 'identical payloads - suspicious'

# ============================================================
Write-Host "`n=== 3. admin list and filters ===" -ForegroundColor Cyan
$cs101 = CourseIdByCode 'CS101' $admin
$cs105 = CourseIdByCode 'CS105' $admin
$cs106 = CourseIdByCode 'CS106' $admin
Check 'course codes resolve for fixtures' (($cs101 -gt 0) -and ($cs105 -gt 0) -and ($cs106 -gt 0)) ("cs101=$cs101 cs105=$cs105 cs106=$cs106")

$r = Api 'GET' '/exam' $admin $null
Check 'admin sees all four seeded exams' ((Cnt $r.data) -eq 4) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/exam?examType=FINAL' $admin $null
Check 'examType filter -> 3 finals' ((Cnt $r.data) -eq 3) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/exam?examType=MAKEUP' $admin $null
Check 'examType filter -> 1 makeup' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))
$r = Api 'GET' "/exam?courseId=$cs101" $admin $null
Check 'courseId filter -> 1 exam' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/exam?status=1' $admin $null
Check 'status filter -> 4 active' ((Cnt $r.data) -eq 4) ("rows=" + (Cnt $r.data))
$r = Api 'GET' ('/exam?term=' + $TERM) $admin $null
Check 'term filter -> all four are in that term' ((Cnt $r.data) -eq 4) ("rows=" + (Cnt $r.data))
$r = Api 'GET' "/exam/course/$cs101" $admin $null
Check 'by-course query works' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/exam/999999' $admin $null
Check 'unknown exam -> business error' ($r.code -ne '200') (Brief $r)
$r = Api 'GET' '/exam/course/999999' $admin $null
Check 'unknown course -> empty list' ((Cnt $r.data) -eq 0) (Brief $r)

# ============================================================
Write-Host "`n=== 4. conflict detection: HALF-OPEN interval (the key rule) ===" -ForegroundColor Cyan
$seeded = @((Api 'GET' "/exam/course/$cs101" $admin $null).data)[0]
Check 'seeded CS101 exam is queryable' ($null -ne $seeded) 'not found'
$startS = ParseIso $seeded.examTime
$endS = $startS.AddMinutes([int]$seeded.durationMinutes)
$roomId = [int]$seeded.roomId

# overlapping -> room conflict
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; examTime = (Iso $startS); durationMinutes = 120; roomId = $roomId }
Check 'same room + same time -> conflict' ($r.data.conflict -eq $true) (Brief $r)
Check 'the room conflict is reported' ((Cnt $r.data.roomConflicts) -ge 1) (Brief $r)
Check 'the room conflict names the clashing course' (@($r.data.roomConflicts)[0].courseCode -eq 'CS101') (Brief $r)

#  boundary: candidate STARTS exactly when the seeded exam ENDS
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; examTime = (Iso $endS); durationMinutes = 60; roomId = $roomId }
# NOTE: also require code 200 -- "no conflicts" is trivially true when the request itself
# failed to parse, so a bare count check would pass for the wrong reason.
Check 'touching after (start == end) -> NO room conflict' ($r.code -eq '200' -and (Cnt $r.data.roomConflicts) -eq 0) (Brief $r)

# one minute earlier -> overlaps again
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; examTime = (Iso ($endS.AddMinutes(-1))); durationMinutes = 60; roomId = $roomId }
Check 'one minute before the end -> room conflict (proves the boundary is real)' ($r.code -eq '200' -and (Cnt $r.data.roomConflicts) -ge 1) (Brief $r)

#  boundary: candidate ENDS exactly when the seeded exam STARTS
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; examTime = (Iso ($startS.AddMinutes(-120))); durationMinutes = 120; roomId = $roomId }
Check 'touching before (end == start) -> NO room conflict' ($r.code -eq '200' -and (Cnt $r.data.roomConflicts) -eq 0) (Brief $r)

# a different room at the same time -> no room conflict
$rooms = @((Api 'GET' '/room?pageNum=1&pageSize=5' $admin $null).data.list)
$otherRoom = ($rooms | Where-Object { [int]$_.id -ne $roomId })[0]
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; examTime = (Iso $startS); durationMinutes = 120; roomId = [int]$otherRoom.id }
Check 'another room at the same time -> NO room conflict' ($r.code -eq '200' -and (Cnt $r.data.roomConflicts) -eq 0) (Brief $r)

# student dimension: CS102 and CS101 share the student 2023001
$cs102 = CourseIdByCode 'CS102' $admin
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs102; examTime = (Iso $startS); durationMinutes = 120 }
Check 'shared students + overlapping time -> student conflict' ($r.code -eq '200' -and (Cnt $r.data.studentConflicts) -ge 1) (Brief $r)
Check 'the student conflict names CS101' (
  @($r.data.studentConflicts | Where-Object { $_.courseCode -eq 'CS101' }).Count -ge 1
) (Brief $r)

# a far-future empty slot -> no conflict at all
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; examTime = (Iso (Get-Date).AddDays(200).Date.AddHours(9)); durationMinutes = 120 }
Check 'a far-future empty slot has no conflict' ($r.code -eq '200' -and $r.data.conflict -eq $false) (Brief $r)

# bad input
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; examTime = 'not-a-time'; durationMinutes = 120 }
Check 'a malformed examTime is rejected' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' '/exam/check' $admin @{ courseId = $cs105; durationMinutes = 120 }
Check 'a missing examTime is rejected' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 5. write flow: create -> update -> delete ===" -ForegroundColor Cyan
$freeStart = (Get-Date).AddDays(200).Date.AddHours(9)
$newExam = @{
  courseId = $cs105; examType = 'FINAL'; examTime = (Iso $freeStart)
  durationMinutes = 120; roomId = [int]$otherRoom.id; seatRange = ('C' + $CN_AREA + '01-40')
  invigilator = $CN_ZHAO; status = 1; remark = $FIX_MARK
}
$r = Api 'POST' '/exam' $admin $newExam
Check 'admin creates an exam -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'the created exam comes back with an id' ($null -ne $r.data.id) (Brief $r)
Check 'the created exam defaults to active' ([int]$r.data.status -eq 1) ("status=" + $r.data.status)
Check 'the created exam carries the Chinese type label' ($r.data.typeLabel -eq $S_FINAL) ("typeLabel=" + $r.data.typeLabel)
$examId = [int]$r.data.id

$r = Api 'GET' "/exam/course/$cs105" $admin $null
Check 'the new exam is queryable by course' ((Cnt $r.data) -eq 1) ("rows=" + (Cnt $r.data))

# duplicate (same course + type)
$r = Api 'POST' '/exam' $admin $newExam
Check 'a duplicate FINAL for the same course is refused' ($r.code -ne '200') (Brief $r)
Check 'the duplicate message mentions an existing active one' ($r.msg.Contains($S_EXISTS)) ("msg=" + $r.msg)

# room conflict on create
$r = Api 'POST' '/exam' $admin @{
  courseId = $cs106; examType = 'FINAL'; examTime = (Iso $freeStart)
  durationMinutes = 120; roomId = [int]$otherRoom.id; remark = $FIX_MARK
}
Check 'creating into an occupied room is refused' ($r.code -ne '200') (Brief $r)
Check 'the refusal says ' ($r.msg.Contains($S_CLASH)) ("msg=" + $r.msg)
$r = Api 'GET' "/exam/course/$cs106" $admin $null
Check 'the refused exam was not created' ((Cnt $r.data) -eq 0) ("rows=" + (Cnt $r.data))

# update
$r = Api 'PUT' '/exam' $admin @{ id = $examId; seatRange = ('D' + $CN_AREA + '01-20'); invigilator = $CN_SUN }
Check 'admin updates the exam -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'GET' "/exam/$examId" $admin $null
Check 'the update persisted the seat range' ($r.data.seatRange -eq ('D' + $CN_AREA + '01-20')) ($r.data | ConvertTo-Json -Compress)
Check 'the update persisted the invigilator' ($r.data.invigilator -eq $CN_SUN) ($r.data | ConvertTo-Json -Compress)
Check 'a partial update kept the required fields' (
  $null -ne $r.data.examTime -and [int]$r.data.durationMinutes -eq 120 -and [int]$r.data.courseId -eq $cs105
) ($r.data | ConvertTo-Json -Compress)

# update into a conflicting slot must be refused and must NOT be applied
$r = Api 'PUT' '/exam' $admin @{ id = $examId; examTime = (Iso $startS); durationMinutes = 120; roomId = $roomId }
Check 'updating into a conflicting slot is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'GET' "/exam/$examId" $admin $null
Check 'the refused update did not move the exam time' ((ParseIso $r.data.examTime) -eq $freeStart) ("examTime=" + $r.data.examTime)

# update validation
$r = Api 'PUT' '/exam' $admin @{ seatRange = 'x' }
Check 'update without id is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'PUT' '/exam' $admin @{ id = 999999; seatRange = 'x' }
Check 'updating an unknown exam is refused' ($r.code -ne '200') (Brief $r)
Check 'the unknown-exam message says it does not exist' ($r.msg.Contains($S_NOTFOUND)) ("msg=" + $r.msg)

# ============================================================
Write-Host "`n=== 6. create validation ===" -ForegroundColor Cyan
# NOTE: hashtable `+` THROWS on duplicate keys, so each variant is a clone with one key set.
$bad = @{ courseId = $cs106; examType = 'FINAL'; examTime = (Iso ((Get-Date).AddDays(300).Date.AddHours(9))); durationMinutes = 120; remark = $FIX_MARK }
$v = $bad.Clone(); $v['examType'] = 'BOGUS'
$r = Api 'POST' '/exam' $admin $v
Check 'an invalid examType is refused' ($r.code -ne '200') (Brief $r)
$v = $bad.Clone(); $v['durationMinutes'] = 0
$r = Api 'POST' '/exam' $admin $v
Check 'a zero duration is refused' ($r.code -ne '200') (Brief $r)
$v = $bad.Clone(); $v['durationMinutes'] = 540
$r = Api 'POST' '/exam' $admin $v
Check 'a 9-hour duration is refused' ($r.code -ne '200') (Brief $r)
$v = $bad.Clone(); $v['roomId'] = 999999
$r = Api 'POST' '/exam' $admin $v
Check 'an unknown room is refused' ($r.code -ne '200') (Brief $r)
$v = $bad.Clone(); $v.Remove('courseId')
$r = Api 'POST' '/exam' $admin $v
Check 'a missing courseId is refused' ($r.code -ne '200') (Brief $r)
$v = $bad.Clone(); $v['courseId'] = 999999
$r = Api 'POST' '/exam' $admin $v
Check 'an unknown course is refused' ($r.code -ne '200') (Brief $r)
$v = $bad.Clone(); $v.Remove('examTime')
$r = Api 'POST' '/exam' $admin $v
Check 'a missing examTime is refused' ($r.code -ne '200') (Brief $r)

# a slot with no room is legitimate ("room pending")
$noRoom = @{ courseId = $cs106; examType = 'FINAL'; durationMinutes = 120; examTime = (Iso ((Get-Date).AddDays(300).Date.AddHours(9))) }
$r = Api 'POST' '/exam' $admin $noRoom
Check 'a slot with no room is accepted (room may be pending)' ($r.code -eq '200') (Brief $r)
if ($r.code -eq '200') {
  Check 'the pending-room exam has a null roomId' ($null -eq $r.data.roomId) ($r.data | ConvertTo-Json -Compress)
  Api 'DELETE' ("/exam/" + [int]$r.data.id) $admin $null | Out-Null
}

# ============================================================
Write-Host "`n=== 7. delete + cleanup ===" -ForegroundColor Cyan
$r = Api 'DELETE' "/exam/$examId" $admin $null
Check 'admin deletes the exam -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'GET' "/exam/$examId" $admin $null
Check 'the deleted exam is gone' ($r.code -ne '200') (Brief $r)
$r = Api 'DELETE' '/exam/999999' $admin $null
Check 'deleting an unknown exam does not 500' ($r.status -eq 200) (Brief $r)

Cleanup
$r = Api 'GET' "/exam/course/$cs105" $admin $null
Check 'fixtures removed from CS105' ((Cnt $r.data) -eq 0) ("rows=" + (Cnt $r.data))
$r = Api 'GET' "/exam/course/$cs106" $admin $null
Check 'fixtures removed from CS106' ((Cnt $r.data) -eq 0) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/exam' $admin $null
Check 'the four seeded exams are intact' ((Cnt $r.data) -eq 4) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/exam/my' $stu1 $null
Check 'the student still sees the four seeded exams' ((Cnt $r.data) -eq 4) ("rows=" + (Cnt $r.data))

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
