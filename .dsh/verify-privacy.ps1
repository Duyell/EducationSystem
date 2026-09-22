# Privacy / ownership / range rules added on 2026-09-22 (author-confirmed).
#
# Covers three things that previously had NO server-side enforcement (only UI constraints):
#   1. evaluation anonymity   -- a teacher sees the content but never WHO submitted it
#   2. evaluation ownership   -- only your own selected courses; the teacher is decided by the course
#   3. score range 0..100     -- out-of-range grades are rejected and nothing is written
#
# Asset note: it writes ONE evaluation (the positive case, to prove ownership really allows it)
# and removes it again through the mysql client, then verifies the cleanup. Everything else only
# reads, so the whole script is repeatable.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI).
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe'
$pass = 0; $fail = 0

# Chinese phrases built from code points: this file must stay ASCII-only (PS 5.1 parses .ps1 as ANSI,
# and a stray multi-byte character can silently swallow the NEXT line of code -- see the dev log).
function Cn([int[]]$points) {
  return -join ($points | ForEach-Object { [char]$_ })
}
$MSG_NOT_SELECTED = Cn @(0x8fd8, 0x6ca1, 0x6709, 0x9009, 0x8fd9, 0x95e8, 0x8bfe)   # "not selected this course"
$MSG_ALREADY_EVAL = Cn @(0x5df2, 0x7ecf, 0x8bc4, 0x4ef7, 0x8fc7)                 # "already evaluated"

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + "  -> " + $detail) -ForegroundColor Red }
}

function Sql([string]$sql) {
  $out = & $mysql -uroot -p123456 -D edujwxt -N -B -e $sql 2>$null
  return @($out | Where-Object { $_ -ne $null -and "$_".Trim() -ne '' })
}

function Login($u, $p) {
  try {
    $r = Invoke-RestMethod -Uri "$base/login" -Method POST -ContentType 'application/json' `
      -Body (@{ username = $u; password = $p } | ConvertTo-Json) -TimeoutSec 15
    return $r.token
  } catch { return $null }
}

function Api($method, $path, $token, $body) {
  try {
    $headers = @{}
    if ($token) { $headers['token'] = $token }
    $args = @{ Uri = ($base + $path); Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 20 }
    if ($body) {
      $args['ContentType'] = 'application/json; charset=utf-8'
      $args['Body'] = $body
    }
    $r = Invoke-WebRequest @args
    $txt = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    return @{ status = [int]$r.StatusCode; body = $txt }
  } catch {
    $st = 0
    try { $st = [int]$_.Exception.Response.StatusCode.value__ } catch { }
    return @{ status = $st; body = $_.Exception.Message }
  }
}

Write-Host "`n=== logins ===" -ForegroundColor Cyan
$student = Login '2023001' '123456'
$teacher1 = Login '10001' '123456'
$teacher2 = Login '10004' '123456'
Check 'student 2023001 login' ($null -ne $student)
Check 'teacher 10001 login' ($null -ne $teacher1)
Check 'teacher 10004 login' ($null -ne $teacher2)

# ---------------------------------------------------------------- 1. anonymity
Write-Host "`n=== 1. evaluation anonymity (teacher sees content, not the submitter) ===" -ForegroundColor Cyan
$r = Api 'GET' '/evaluate/teacher?pageNum=1&pageSize=50' $teacher1 $null
Check 'teacher GET /evaluate/teacher -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status)
Check 'teacher payload has rows (seed has evaluations)' ($r.body -match '"total":[1-9]') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
Check 'teacher payload exposes NO studentId' ($r.body -notmatch '"studentId"\s*:\s*"[0-9]') 'studentId leaked to the teacher'
Check 'teacher payload exposes NO studentName' ($r.body -notmatch '"studentName"\s*:\s*"[^"]') 'studentName leaked to the teacher'
Check 'teacher payload still carries content/score' (($r.body -match '"score"') -and ($r.body -match '"courseName"')) 'content fields missing'

$r = Api 'GET' '/evaluate/teacher/avg' $teacher1 $null
Check 'teacher avg still works' ($r.status -eq 200 -and $r.body -match '"code":"200"') ("status=" + $r.status + " body=" + $r.body.Substring(0, [Math]::Min(120, $r.body.Length)))

# a student may still see their own submission (no need to anonymise a student from themselves)
$r = Api 'GET' '/evaluate/my?pageNum=1&pageSize=50' $student $null
Check 'student still sees own evaluations with own id' ($r.body -match '"studentId"\s*:\s*"2023001"') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))

# ---------------------------------------------------------------- 2. ownership
Write-Host "`n=== 2. evaluation ownership (only your own selected courses) ===" -ForegroundColor Cyan
# course 10 (CS107) is NOT selected by 2023001 in the seed data
$r = Api 'POST' '/evaluate' $student '{"courseId":10,"teacherId":"10001","score":5,"content":"x"}'
Check 'evaluating a course you did not select is rejected' ($r.body -notmatch '"code":"200"') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
Check 'rejection explains why' ($r.body -match $MSG_NOT_SELECTED) ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))

# course 1 was already evaluated by 2023001 in the seed data
$r = Api 'POST' '/evaluate' $student '{"courseId":1,"teacherId":"10001","score":5,"content":"x"}'
Check 'evaluating the same course twice is rejected' ($r.body -notmatch '"code":"200"') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
Check 'duplicate rejection explains why' ($r.body -match $MSG_ALREADY_EVAL) ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))

# course 2 is selected by 2023001 and NOT yet evaluated -> allowed, and the teacher is decided by the course
$before = (Sql "select count(*) from teacher_evaluation where course_id=2 and student_id='2023001'") | Select-Object -First 1
Check 'fixture: course 2 not yet evaluated by 2023001' ("$before" -eq '0') ("rows=" + $before)
$r = Api 'POST' '/evaluate' $student '{"courseId":2,"teacherId":"10004","score":5,"content":"ownership probe"}'
Check 'evaluating an own selected course is accepted' ($r.body -match '"code":"200"') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
$owner = (Sql "select teacher_id from teacher_evaluation where course_id=2 and student_id='2023001'") | Select-Object -First 1
Check 'teacher is decided by the course (not by the caller)' ("$owner" -eq '10002') ("stored teacher_id=" + $owner + " (sent 10004, course owner is 10002)")

# cleanup (the only write this script makes)
Sql "delete from teacher_evaluation where course_id=2 and student_id='2023001'" | Out-Null
$after = (Sql "select count(*) from teacher_evaluation where course_id=2 and student_id='2023001'") | Select-Object -First 1
Check 'cleanup removed the probe row' ("$after" -eq '0') ("rows=" + $after)

# ---------------------------------------------------------------- 3. score range
Write-Host "`n=== 3. score range 0..100 ===" -ForegroundColor Cyan
# course 10 belongs to teacher 10001; student 2024002 has no grade there by default
$r = Api 'POST' '/score' $teacher1 '{"courseId":10,"studentId":"2024002","usualScore":101,"examScore":80}'
Check 'usualScore=101 is rejected' ($r.body -notmatch '"code":"200"') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
Check 'rejection mentions the 0..100 rule' ($r.body -match '0~100') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
$wrote = (Sql "select count(*) from score where course_id=10 and student_id='2024002'") | Select-Object -First 1
Check 'nothing was written when validation failed' ("$wrote" -eq '0') ("rows=" + $wrote)

$r = Api 'POST' '/score' $teacher1 '{"courseId":10,"studentId":"2024002","usualScore":-5,"examScore":80}'
Check 'negative score is rejected' ($r.body -notmatch '"code":"200"') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))

Write-Host "`n=== 4. the AI tool schemas advertise the same bound ===" -ForegroundColor Cyan
$s = Api 'GET' '/ai/tools' $teacher1 $null
Check '/ai/tools exposes enter_score' ($s.body -match 'enter_score') 'tool missing'
Check 'enter_score usualScore is bounded 0..100' ($s.body -match '"maximum":100') 'no maximum in schema'
Check 'enter_score documents the 0..100 rule in text' ($s.body -match '0~100') 'description missing the rule'

# ---------------------------------------------------------------- 5. score change log
Write-Host "`n=== 5. score change log (UI edits are traceable too) ===" -ForegroundColor Cyan
# Use course 10 (teacher 10001) + student 2024002, which has no seeded grade there.
$admin = Login 'admin01' '123456'
Check 'admin01 login' ($null -ne $admin)

# clean slate for repeatability
Sql "delete from score where course_id=10 and student_id='2024002'" | Out-Null
Sql "delete from score_change_log where course_id=10 and student_id='2024002'" | Out-Null

$r = Api 'POST' '/score' $teacher1 '{"courseId":10,"studentId":"2024002","usualScore":80,"examScore":90}'
Check 'teacher can enter a grade (HTTP 200)' ($r.status -eq 200 -and $r.body -match '"code":"200"') ($r.body.Substring(0, [Math]::Min(160, $r.body.Length)))
$scoreId = (Sql "select id from score where course_id=10 and student_id='2024002'") | Select-Object -First 1

$r = Api 'PUT' '/score' $teacher1 ('{"id":' + $scoreId + ',"courseId":10,"studentId":"2024002","examScore":30}')
Check 'teacher can change the grade (HTTP 200)' ($r.status -eq 200 -and $r.body -match '"code":"200"') ($r.body.Substring(0, [Math]::Min(160, $r.body.Length)))

$r = Api 'GET' '/score/change-log?page=1&pageSize=50&studentId=2024002' $admin $null
Check 'admin can read the change log' ($r.status -eq 200 -and $r.body -match '"code":"200"') ($r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
Check 'log records the INSERT' ($r.body -match '"operation":"INSERT"') 'no INSERT entry'
Check 'log records the UPDATE with before and after' (($r.body -match '"operation":"UPDATE"') -and ($r.body -match '"beforeExam"') -and ($r.body -match '"afterExam"')) 'no UPDATE detail'
Check 'before/after scores differ (86 -> 50)' (($r.body -match '"beforeTotal":86') -and ($r.body -match '"afterTotal":50')) ($r.body.Substring(0, [Math]::Min(300, $r.body.Length)))
Check 'log records who did it' ($r.body -match '"operatorId":"10001"') 'operator missing'
Check 'log records the source as UI (not AI)' ($r.body -match '"source":"UI"') 'source missing or wrong'

# the change log is admin-only (order-sensitive rule before /score/**)
$r = Api 'GET' '/score/change-log?page=1&pageSize=10' $teacher1 $null
Check 'teacher is refused the change log (403)' ($r.status -eq 403) ("status=" + $r.status)
$r = Api 'GET' '/score/change-log?page=1&pageSize=10' $student $null
Check 'student is refused the change log (403)' ($r.status -eq 403) ("status=" + $r.status)
$r = Api 'GET' '/score/change-log?page=1&pageSize=10' $null $null
Check 'anonymous is refused the change log (401)' ($r.status -eq 401) ("status=" + $r.status)

# AI-driven change must be marked as AI: ask the assistant? That needs a model; the Java test
# (ScoreChangeLogTest.aiToolPathIsLoggedAsAiSource) proves the AI source through the real tool path.

# cleanup: remove the probe grade and its log rows, then prove it is clean (repeatable script)
$r = Api 'DELETE' ('/score/' + $scoreId) $teacher1 $null
Check 'probe grade deleted' ($r.status -eq 200 -and $r.body -match '"code":"200"') ($r.body.Substring(0, [Math]::Min(160, $r.body.Length)))
$deleteLogged = (Sql "select count(*) from score_change_log where course_id=10 and student_id='2024002' and operation='DELETE'") | Select-Object -First 1
Check 'the deletion itself was logged as DELETE' ("$deleteLogged" -eq '1') ("rows=" + $deleteLogged)
Sql "delete from score_change_log where course_id=10 and student_id='2024002'" | Out-Null
$left = (Sql "select count(*) from score_change_log where course_id=10 and student_id='2024002'") | Select-Object -First 1
Check 'log rows cleaned up (script is repeatable)' ("$left" -eq '0') ("rows=" + $left)

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
