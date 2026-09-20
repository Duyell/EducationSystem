# P3 API verification: selection rounds + the six-check selection chain.
#
# Covers the P3 rules the user stated:
#   * only after an admin OPENS a round may a student select; otherwise read-only
#   * during the selection window a student may drop; otherwise a drop needs the
#     supplementary() window
# plus the documented six-check chain, one assertion group per check:
#   1 round open  2 scope  3 credit cap  4 not already passed  5 no time clash  6 capacity
#
# Fixtures live in a throwaway term (2030-2031) so nothing seeded is disturbed, and are
# wiped before AND after the run, making the script re-runnable.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI). Chinese message
# fragments are built from code points -- see the $S_* constants -- so the file stays ASCII.
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe'
$pass = 0; $fail = 0

$TERM_OPEN = '2024-2025-1'
$TERM_DROPONLY = '2024-2025-2'
$TERM_CLOSED = '2025-2026-1'
$TERM_FIX = '2030-2031'

$C_OPEN = 'VERIFY-P3-OPEN'
$C_BIG = 'VERIFY-P3-BIG'
$C_PASSED = 'VERIFY-P3-PASSED'
$C_FULL = 'VERIFY-P3-FULL'
$C_A = 'VERIFY-P3-A'
$C_B = 'VERIFY-P3-B'

# NOTE: PowerShell variable names are CASE-INSENSITIVE, so `$STU1` and `$stu1` are the SAME
# variable. Naming the id and the token that way silently overwrote the id with the token,
# which sent a JWT where a student number belonged. Keep ids as $ID1/$ID2.
$ID1 = '2023001'
$ID2 = '2023002'

# --- Chinese fragments, built from code points so this file stays ASCII-only ---
$S_DROPONLY = "$([char]0x8865)$([char]0x9000)$([char]0x9009)"                                  # 
$S_NOROUND = "$([char]0x6CA1)$([char]0x6709)$([char]0x5DF2)$([char]0x5F00)$([char]0x542F)"      # 
$S_CREDITCAP = "$([char]0x5B66)$([char]0x5206)$([char]0x4E0A)$([char]0x9650)"                  # 
$S_PASSED = "$([char]0x5DF2)$([char]0x901A)$([char]0x8FC7)"                                    # 
$S_FULL = "$([char]0x540D)$([char]0x989D)$([char]0x5DF2)$([char]0x6EE1)"                       # 
$S_CLASH = "$([char]0x65F6)$([char]0x95F4)$([char]0x51B2)$([char]0x7A81)"                      # 
$S_NOTSEL = "$([char]0x8FD8)$([char]0x6CA1)$([char]0x6709)$([char]0x9009)"                     # 

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
  $sql = "delete cs from course_selection cs join course c on cs.course_id = c.id where c.term = '$TERM_FIX'; " +
         "delete ct from class_time ct join course c on ct.course_id = c.id where c.term = '$TERM_FIX'; " +
         "delete from selection_round_scope where round_id in (select id from selection_round where term = '$TERM_FIX'); " +
         "delete from selection_round where term = '$TERM_FIX'; " +
         "delete from course where term = '$TERM_FIX';"
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
    body   = $txt
  }
}

function Brief($r) { return ("status=" + $r.status + " code=" + $r.code + " body=" + $r.body.Substring(0, [Math]::Min(160, $r.body.Length))) }
function Cnt($v) { if ($null -eq $v) { return 0 } else { return @($v).Count } }
function Iso([datetime]$d) { return $d.ToString('yyyy-MM-ddTHH:mm:ss') }

function CourseIdByCode($code, $admin) {
  $r = Api 'GET' '/course?page=1&pageSize=300' $admin $null
  $hit = @($r.data.list) | Where-Object { $_.courseCode -eq $code }
  if ((Cnt $hit) -eq 0) { return 0 }
  return [int]$hit[0].id
}

function MakeCourse($admin, $code, $name, $credit, $maxStudent, $term) {
  $body = @{
    courseCode = $code; courseName = $name; teacherId = '10001'; collegeId = 1
    term = $term; credit = $credit; classHour = 32; maxStudent = $maxStudent
  }
  $r = Api 'POST' '/course' $admin $body
  return $r
}

# ============================================================
Write-Host "`n=== 0. fixture cleanup + logins ===" -ForegroundColor Cyan
Cleanup

$admin = Login 'admin01' '123456'
$stu1 = Login $ID1 '123456'
$stu2 = Login $ID2 '123456'
$teacher = Login '10001' '123456'
Check 'admin01 login' ($null -ne $admin)
Check 'student 2023001 login' ($null -ne $stu1)
Check 'student 2023002 login' ($null -ne $stu2)
Check 'teacher 10001 login' ($null -ne $teacher)

# ============================================================
Write-Host "`n=== 1. permissions ===" -ForegroundColor Cyan
$anonPaths = @(
  @('GET', '/selection-round'),
  @('GET', "/selection-round/current?term=$TERM_OPEN"),
  @('GET', '/selection-round/1/scope'),
  @('GET', "/course-selection/selectable?term=$TERM_OPEN")
)
foreach ($x in $anonPaths) {
  $r = Api $x[0] $x[1] $null $null
  Check ("anon " + $x[0] + " " + $x[1] + " -> 401") ($r.status -eq 401) (Brief $r)
}
$r = Api 'POST' '/selection-round' $null @{ roundName = 'x'; term = $TERM_FIX }
Check 'anon POST /selection-round -> 401' ($r.status -eq 401) (Brief $r)

# student: may read own status, must NOT manage rounds
$r = Api 'GET' "/selection-round/current?term=$TERM_OPEN" $stu1 $null
Check 'student GET /selection-round/current -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'GET' "/course-selection/selectable?term=$TERM_OPEN" $stu1 $null
Check 'student GET /course-selection/selectable -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$stuDenied = @(
  @('GET', '/selection-round'), @('GET', '/selection-round/1'),
  @('GET', '/selection-round/1/scope'), @('DELETE', '/selection-round/1')
)
foreach ($x in $stuDenied) {
  $r = Api $x[0] $x[1] $stu1 $null
  Check ("student " + $x[0] + " " + $x[1] + " -> 403") ($r.status -eq 403) (Brief $r)
}
$r = Api 'POST' '/selection-round' $stu1 @{ roundName = 'x'; term = $TERM_FIX }
Check 'student POST /selection-round -> 403' ($r.status -eq 403) (Brief $r)
$r = Api 'POST' '/selection-round/1/status' $stu1 @{ status = 1 }
Check 'student POST /selection-round/{id}/status -> 403' ($r.status -eq 403) (Brief $r)

# teacher: no selection features at all
$tDenied = @(
  @('GET', '/selection-round'), @('GET', "/selection-round/current?term=$TERM_OPEN"),
  @('GET', "/course-selection/selectable?term=$TERM_OPEN"), @('GET', '/course-selection/my')
)
foreach ($x in $tDenied) {
  $r = Api $x[0] $x[1] $teacher $null
  Check ("teacher " + $x[1] + " -> 403") ($r.status -eq 403) (Brief $r)
}

$r = Api 'GET' '/selection-round' $admin $null
Check 'admin GET /selection-round -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)

# ============================================================
Write-Host "`n=== 2. the three seeded round states ===" -ForegroundColor Cyan
$r = Api 'GET' "/selection-round/current?term=$TERM_OPEN" $stu1 $null
Check 'open term: roundOpen' ($r.data.roundOpen -eq $true) (Brief $r)
Check 'open term: canSelect' ($r.data.canSelect -eq $true) (Brief $r)
Check 'open term: canDrop' ($r.data.canDrop -eq $true) (Brief $r)
Check 'open term: exposes round id' ($null -ne $r.data.roundId) (Brief $r)
Check 'open term: exposes the 30 credit cap' ([decimal]$r.data.maxCredits -eq 30.0) ("maxCredits=" + $r.data.maxCredits)

$r = Api 'GET' "/selection-round/current?term=$TERM_DROPONLY" $stu1 $null
Check 'drop-only term: roundOpen' ($r.data.roundOpen -eq $true) (Brief $r)
Check 'drop-only term: canSelect is false' ($r.data.canSelect -eq $false) (Brief $r)
Check 'drop-only term: canDrop is true' ($r.data.canDrop -eq $true) (Brief $r)
Check 'drop-only term: reason mentions the supplementary window' ($r.msg -ne $null -and $r.data.reason.Contains($S_DROPONLY)) ("reason=" + $r.data.reason)

$r = Api 'GET' "/selection-round/current?term=$TERM_CLOSED" $stu1 $null
Check 'closed term: not roundOpen' ($r.data.roundOpen -eq $false) (Brief $r)
Check 'closed term: cannot select' ($r.data.canSelect -eq $false) (Brief $r)
Check 'closed term: cannot drop' ($r.data.canDrop -eq $false) (Brief $r)
Check 'closed term: reason says no open round' ($r.data.reason.Contains($S_NOROUND)) ("reason=" + $r.data.reason)
# NOTE: derived record methods are NOT serialized (no `readOnly` field on the wire) --
# the caller must derive it, exactly like P1's AuditResult.satisfied().
Check 'closed term: is read-only' ((-not $r.data.canSelect) -and (-not $r.data.canDrop)) (Brief $r)

$r = Api 'GET' '/selection-round/current?term=2033-2034' $stu1 $null
Check 'term without any round: read-only' ((-not $r.data.canSelect) -and (-not $r.data.canDrop)) (Brief $r)

# admin may query on behalf of a student
$r = Api 'GET' "/selection-round/current?term=$TERM_OPEN&studentId=$ID2" $admin $null
Check 'admin can query another student status' ($r.status -eq 200 -and $r.data.canSelect -eq $true) (Brief $r)

# scopes come back with the round list
$r = Api 'GET' '/selection-round' $admin $null
$rounds = @($r.data)
$withScope = $rounds | Where-Object { (Cnt $_.scopes) -gt 0 }
Check 'round list includes the seeded scope' ((Cnt $withScope) -ge 1) ("rounds=" + (Cnt $rounds))
Check 'seeded scope is grade 2023' ((Cnt $withScope) -ge 1 -and $withScope[0].scopes[0].grade -eq '2023') 'no grade 2023 scope'

# ============================================================
Write-Host "`n=== 3. fixture setup (throwaway term $TERM_FIX) ===" -ForegroundColor Cyan
$mk = MakeCourse $admin $C_OPEN 'VERIFY P3 open course' 2.0 50 $TERM_FIX
Check 'create fixture course (open)' ($mk.code -eq '200') (Brief $mk)
$mk = MakeCourse $admin $C_BIG 'VERIFY P3 over-cap course' 31.0 50 $TERM_FIX
Check 'create fixture course (over credit cap)' ($mk.code -eq '200') (Brief $mk)
$mk = MakeCourse $admin $C_PASSED 'VERIFY P3 already-passed course' 2.0 50 $TERM_FIX
Check 'create fixture course (code already passed) fails uniqueness? no' ($mk.code -eq '200') (Brief $mk)
$mk = MakeCourse $admin $C_FULL 'VERIFY P3 full course' 2.0 1 $TERM_FIX
Check 'create fixture course (capacity 1)' ($mk.code -eq '200') (Brief $mk)
$mk = MakeCourse $admin $C_A 'VERIFY P3 slot A' 2.0 50 $TERM_FIX
Check 'create fixture course (slot A)' ($mk.code -eq '200') (Brief $mk)
$mk = MakeCourse $admin $C_B 'VERIFY P3 slot B' 2.0 50 $TERM_FIX
Check 'create fixture course (slot B)' ($mk.code -eq '200') (Brief $mk)

# The "already passed" fixture must carry a course code the student has passed (CS101).
# Grab its id FIRST: after the rename, looking up "CS101" would find the seeded CS101 row
# instead (which the student has also already selected, masking the check under test).
$idPassed = CourseIdByCode $C_PASSED $admin
& $mysql -uroot -p123456 -D edujwxt -N -B -e "update course set course_code = 'CS101' where id = $idPassed;" 2>&1 | Out-Null

$idOpen = CourseIdByCode $C_OPEN $admin
$idBig = CourseIdByCode $C_BIG $admin
$idFull = CourseIdByCode $C_FULL $admin
$idA = CourseIdByCode $C_A $admin
$idB = CourseIdByCode $C_B $admin
Check 'fixture courses are queryable' (($idOpen -gt 0) -and ($idBig -gt 0) -and ($idPassed -gt 0) -and ($idFull -gt 0) -and ($idA -gt 0) -and ($idB -gt 0)) ("open=$idOpen big=$idBig passed=$idPassed full=$idFull a=$idA b=$idB")

# Timetables: A occupies Monday 1-2; B occupies Monday 2-3 -> they overlap at period 2.
$now = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
& $mysql -uroot -p123456 -D edujwxt -N -B -e "insert into class_time(course_id, weekday, start_period, end_period, start_week, end_week, room_id) values ($idA, 1, 1, 2, 1, 16, (select id from room where room_name = concat(char(0xE6,0x95,0x99 using utf8mb4), '1-101') limit 1)), ($idB, 1, 2, 3, 1, 16, (select id from room where room_name = concat(char(0xE6,0x95,0x99 using utf8mb4), '1-102') limit 1));" 2>&1 | Out-Null

# Round: open now, 30 credit cap, no scope (= unrestricted)
$roundBody = @{
  roundName = 'VERIFY P3 fixture round'; term = $TERM_FIX
  selectStart = Iso (Get-Date).AddDays(-1); selectEnd = Iso (Get-Date).AddDays(10)
  status = 1; maxCredits = 30.0
}
$r = Api 'POST' '/selection-round' $admin $roundBody
Check 'create fixture round -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
Check 'a new round is created closed unless asked otherwise? (status honoured)' ([int]$r.data.status -eq 1) ("status=" + $r.data.status)
$roundId = [int]$r.data.id

$r = Api 'GET' "/selection-round/current?term=$TERM_FIX" $stu1 $null
Check 'student can select in the fixture term' ($r.data.canSelect -eq $true) (Brief $r)

# ============================================================
Write-Host "`n=== 4. chain checks 3/4/6 via listSelectable and via select ===" -ForegroundColor Cyan
$r = Api 'GET' "/course-selection/selectable?term=$TERM_FIX" $stu1 $null
$rows = @($r.data)
Check 'selectable list covers all fixture courses' ((Cnt $rows) -eq 6) ("rows=" + (Cnt $rows))
$rowOpen = $rows | Where-Object { $_.course.id -eq $idOpen }
$rowBig = $rows | Where-Object { $_.course.id -eq $idBig }
Check 'open fixture course is selectable' ($rowOpen.selectable -eq $true) ("reason=" + $rowOpen.reason)

# check 3: credit cap
Check 'over-cap course is blocked in the list' ($rowBig.selectable -eq $false) 'selectable unexpectedly true'
Check 'over-cap reason mentions the credit cap' ($rowBig.reason.Contains($S_CREDITCAP)) ("reason=" + $rowBig.reason)
$r = Api 'POST' "/course-selection/select/$idBig" $stu1 $null
Check 'selecting the over-cap course is refused' ($r.code -ne '200') (Brief $r)
Check 'over-cap refusal names the cap' ($r.msg.Contains($S_CREDITCAP)) ("msg=" + $r.msg)

# check 4: already passed the same course code
$rowPassed = $rows | Where-Object { $_.course.id -eq $idPassed }
Check 'already-passed course is blocked in the list' ($rowPassed.selectable -eq $false) 'selectable unexpectedly true'
Check 'already-passed reason mentions ' ($rowPassed.reason.Contains($S_PASSED)) ("reason=" + $rowPassed.reason)
$r = Api 'POST' "/course-selection/select/$idPassed" $stu1 $null
Check 'selecting an already-passed course is refused' ($r.code -ne '200') (Brief $r)
Check 'refusal says it is already passed' ($r.msg.Contains($S_PASSED)) ("msg=" + $r.msg)

# check 6: capacity - fill it from another student through the real API first
$r = Api 'POST' "/course-selection/select/$idFull" $stu2 $null
Check 'second student fills the capacity-1 course' ($r.code -eq '200') (Brief $r)
$r = Api 'POST' "/course-selection/select/$idFull" $stu1 $null
Check 'selecting a full course is refused' ($r.code -ne '200') (Brief $r)
Check 'refusal says the course is full' ($r.msg.Contains($S_FULL)) ("msg=" + $r.msg)

# ============================================================
Write-Host "`n=== 5. chain check 5: student time conflict ===" -ForegroundColor Cyan
$r = Api 'POST' "/course-selection/select/$idA" $stu1 $null
Check 'selecting slot A succeeds' ($r.code -eq '200') (Brief $r)
$r = Api 'POST' "/course-selection/select/$idB" $stu1 $null
Check 'selecting the overlapping slot B is refused' ($r.code -ne '200') (Brief $r)
Check 'refusal is a time conflict' ($r.msg.Contains($S_CLASH)) ("msg=" + $r.msg)
Check 'the conflict names the clashing course code' ($r.msg.Contains($C_A)) ("msg=" + $r.msg)
Check 'the conflict describes weekday/periods/weeks' ($r.msg -match '1-2') ("msg=" + $r.msg)

# adjacency must NOT conflict: shrink B to period 3-4 only (adjacent to A's 1-2)
& $mysql -uroot -p123456 -D edujwxt -N -B -e "update class_time set start_period = 3, end_period = 4 where course_id = $idB;" 2>&1 | Out-Null
$r = Api 'POST' "/course-selection/select/$idB" $stu1 $null
Check 'adjacent slot does NOT conflict and selects fine' ($r.code -eq '200') (Brief $r)

# a course with no timetable at all can never clash
$r = Api 'POST' "/course-selection/select/$idOpen" $stu1 $null
Check 'course without a timetable selects fine' ($r.code -eq '200') (Brief $r)
Check 'selection records the hit round' ($null -ne (Api 'GET' "/selection-round/current?term=$TERM_FIX" $stu1 $null).data.roundId) 'no round id'

# duplicate selection
$r = Api 'POST' "/course-selection/select/$idOpen" $stu1 $null
Check 'selecting the same course twice is refused' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 6. chain check 1: round must be OPEN ===" -ForegroundColor Cyan
$r = Api 'POST' "/selection-round/$roundId/status" $admin @{ status = 0 }
Check 'admin closes the round -> 200' ($r.code -eq '200') (Brief $r)
$r = Api 'GET' "/selection-round/current?term=$TERM_FIX" $stu1 $null
Check 'after closing: cannot select' ($r.data.canSelect -eq $false) (Brief $r)
# NOTE: select a course the student has NOT already selected. Picking A here would trip the
# earlier duplicate-selection guard ("already selected") instead of the round check this
# section is about -- a reminder that a negative assertion can pass/fail on the WRONG guard.
$r = Api 'POST' "/course-selection/select/$idBig" $stu1 $null
Check 'selecting while closed is refused' ($r.code -ne '200') (Brief $r)
Check 'refusal says no round is open' ($r.msg.Contains($S_NOROUND)) ("msg=" + $r.msg)
$r = Api 'DELETE' "/course-selection/$idA" $stu1 $null
Check 'dropping while closed is refused' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 7. drop needs the selection window or the supplementary window ===" -ForegroundColor Cyan
# Re-open the round with the SELECT window in the past and the DROP window active.
$r = Api 'PUT' '/selection-round' $admin @{
  id = $roundId; roundName = 'VERIFY P3 fixture round'; term = $TERM_FIX
  selectStart = Iso (Get-Date).AddDays(-30); selectEnd = Iso (Get-Date).AddDays(-20)
  dropStart = Iso (Get-Date).AddDays(-1); dropEnd = Iso (Get-Date).AddDays(10)
  status = 1; maxCredits = 30.0
}
Check 'round updated to supplementary mode -> 200' ($r.code -eq '200') (Brief $r)

$r = Api 'GET' "/selection-round/current?term=$TERM_FIX" $stu1 $null
Check 'supplementary mode: cannot select' ($r.data.canSelect -eq $false) (Brief $r)
Check 'supplementary mode: can drop' ($r.data.canDrop -eq $true) (Brief $r)
Check 'supplementary reason mentions the window' ($r.data.reason.Contains($S_DROPONLY)) ("reason=" + $r.data.reason)

$r = Api 'POST' "/course-selection/select/$idBig" $stu1 $null
Check 'selecting in supplementary mode is refused' ($r.code -ne '200') (Brief $r)
Check 'supplementary refusal mentions the window' ($r.msg.Contains($S_DROPONLY)) ("msg=" + $r.msg)

$r = Api 'DELETE' "/course-selection/$idA" $stu1 $null
Check 'dropping in supplementary mode is allowed' ($r.code -eq '200') (Brief $r)
$r = Api 'GET' "/course-selection/my-ids" $stu1 $null
Check 'the dropped course is gone' (-not (@($r.data) -contains $idA)) ("ids=" + (@($r.data) -join ','))

# dropping something never selected (use the full course: 2023001 never got into it)
$r = Api 'DELETE' "/course-selection/$idFull" $stu1 $null
Check 'dropping a course never selected is refused' ($r.code -ne '200') (Brief $r)
Check 'the refusal explains nothing was selected' ($r.msg.Contains($S_NOTSEL)) ("msg=" + $r.msg)

# ============================================================
Write-Host "`n=== 8. scope restricts who may select ===" -ForegroundColor Cyan
# Add a scope that excludes 2023001 (grade 2029) -> even an open round must refuse.
$r = Api 'POST' '/selection-round/scope' $admin @{ roundId = $roundId; grade = '2029' }
Check 'add a restrictive scope -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$scopeId = [int]$r.data.id

# Put the round back into selection mode so only the scope can block.
$r = Api 'PUT' '/selection-round' $admin @{
  id = $roundId; roundName = 'VERIFY P3 fixture round'; term = $TERM_FIX
  selectStart = Iso (Get-Date).AddDays(-1); selectEnd = Iso (Get-Date).AddDays(10)
  status = 1; maxCredits = 30.0
}
Check 'round back in selection mode' ($r.code -eq '200') (Brief $r)
$r = Api 'GET' "/selection-round/current?term=$TERM_FIX" $stu1 $null
Check 'out-of-scope student cannot select' ($r.data.canSelect -eq $false) (Brief $r)
Check 'out-of-scope reason mentions the scope' ($r.data.reason -match '2029' -or $r.data.reason.Length -gt 0) ("reason=" + $r.data.reason)
$r = Api 'POST' "/course-selection/select/$idOpen" $stu1 $null
Check 'selecting out of scope is refused' ($r.code -ne '200') (Brief $r)

# An unrestricted scope row is meaningless and must be rejected on purpose.
$r = Api 'POST' '/selection-round/scope' $admin @{ roundId = $roundId }
Check 'an all-empty scope is refused' ($r.code -ne '200') (Brief $r)
# Scope for an unknown round
$r = Api 'POST' '/selection-round/scope' $admin @{ roundId = 999999; grade = '2023' }
Check 'a scope for an unknown round is refused' ($r.code -ne '200') (Brief $r)

$r = Api 'DELETE' "/selection-round/scope/$scopeId" $admin $null
Check 'removing the scope -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'GET' "/selection-round/current?term=$TERM_FIX" $stu1 $null
Check 'removing the scope restores selectability' ($r.data.canSelect -eq $true) (Brief $r)

# ============================================================
Write-Host "`n=== 9. round administration validation ===" -ForegroundColor Cyan
$r = Api 'POST' '/selection-round' $admin @{ roundName = 'VERIFY P3 inverted'; term = $TERM_FIX; selectStart = Iso (Get-Date); selectEnd = Iso (Get-Date).AddDays(-1) }
Check 'inverted select window is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' '/selection-round' $admin @{ roundName = 'VERIFY P3 notime'; term = $TERM_FIX }
Check 'missing select window is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' '/selection-round' $admin @{ roundName = 'VERIFY P3 fixture round'; term = $TERM_FIX; selectStart = Iso (Get-Date); selectEnd = Iso (Get-Date).AddDays(1) }
Check 'duplicate round name in the same term is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' "/selection-round/$roundId/status" $admin @{ status = 7 }
Check 'an invalid status value is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'POST' '/selection-round/999999/status' $admin @{ status = 1 }
Check 'toggling an unknown round is refused' ($r.code -ne '200') (Brief $r)
$r = Api 'GET' '/selection-round/999999' $admin $null
Check 'unknown round detail -> business error' ($r.code -ne '200') (Brief $r)

# ============================================================
Write-Host "`n=== 10. cleanup + seeded data intact ===" -ForegroundColor Cyan
Cleanup
$r = Api 'GET' "/course-selection/selectable?term=$TERM_FIX" $stu1 $null
Check 'fixture term has no courses left' ((Cnt $r.data) -eq 0) ("rows=" + (Cnt $r.data))
$r = Api 'GET' '/selection-round' $admin $null
$leftover = @($r.data) | Where-Object { $_.term -eq $TERM_FIX }
Check 'fixture round removed' ((Cnt $leftover) -eq 0) ("leftover=" + (Cnt $leftover))
$r = Api 'GET' "/selection-round/current?term=$TERM_OPEN" $stu1 $null
Check 'seeded open round still open' ($r.data.canSelect -eq $true) (Brief $r)
$r = Api 'GET' "/course-selection/my-ids" $stu1 $null
Check 'seeded selections intact' ((Cnt $r.data) -eq 6) ("count=" + (Cnt $r.data))

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
