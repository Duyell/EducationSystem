# Academic-warning API assertions (JW-01 section 5).
#
# Covers: payload structure, permissions (student self / teacher 403 / anonymous 401),
# and the idempotent "mark as read" behaviour.
#
# WHY THE ASSERTIONS ARE SHAPED THIS WAY (important)
#   "must notify on first trigger" is deliberately NOT asserted here: the seed data has no failing
#   grades, and warning rows are PERSISTED. A script that fabricated a warning and then asserted
#   "first time must notify" would go red on its second run -- and an unrepeatable check is worse
#   than no check at all. So this script asserts STATE-INDEPENDENT invariants:
#     - payload structure is complete and self-consistent (credits == sum of course credits);
#     - warned must equal (failedCredits >= threshold) -- data driven, repeatable;
#     - after mark-as-read, shouldNotify must be false -- idempotent, repeatable.
#   The state machine itself ("notify once, stay silent until it gets worse") is covered by the Java
#   test duyell.service.AcademicWarningServiceTest, which runs in a rolled-back transaction and is
#   therefore fully repeatable.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI).
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$pass = 0; $fail = 0

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

# returns @{ status = httpStatus; code = businessCode; body = rawText }
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
    $code = 'n/a'
    try { $code = (ConvertFrom-Json $txt).code } catch { }
    return @{ status = [int]$r.StatusCode; code = $code; body = $txt }
  } catch {
    $st = 0
    try { $st = [int]$_.Exception.Response.StatusCode.value__ } catch { }
    return @{ status = $st; code = 'err'; body = $_.Exception.Message }
  }
}

Write-Host "`n=== logins ===" -ForegroundColor Cyan
$student = Login '2023001' '123456'
$student2 = Login '2023002' '123456'
$teacher = Login '10001' '123456'
$admin = Login 'admin01' '123456'
Check 'student 2023001 login' ($null -ne $student)
Check 'student 2023002 login' ($null -ne $student2)
Check 'teacher 10001 login' ($null -ne $teacher)
Check 'admin01 login' ($null -ne $admin)

Write-Host "`n=== 1. anonymous / teacher are rejected ===" -ForegroundColor Cyan
$r = Api 'GET' '/academic-warning/my' $null $null
Check 'anonymous GET -> 401' ($r.status -eq 401) ("status=" + $r.status)
$r = Api 'GET' '/academic-warning/my' $teacher $null
Check 'teacher GET -> 403 (teachers have no warning feature)' ($r.status -eq 403) ("status=" + $r.status)
$r = Api 'POST' '/academic-warning/my/read' $teacher $null
Check 'teacher POST read -> 403' ($r.status -eq 403) ("status=" + $r.status)

Write-Host "`n=== 2. student gets own status (structure) ===" -ForegroundColor Cyan
$r = Api 'GET' '/academic-warning/my' $student $null
Check 'student GET -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status)
Check 'business code is 200' ($r.code -eq '200') ("code=" + $r.code)
$d = (ConvertFrom-Json $r.body).data
foreach ($field in @('warned', 'shouldNotify', 'failedCredits', 'failedCourseCount', 'threshold', 'courses')) {
  Check ("payload has field: " + $field) ($null -ne $d.$field) ("body=" + $r.body.Substring(0, [Math]::Min(200, $r.body.Length)))
}
Check 'threshold is configured (>0)' ([double]$d.threshold -gt 0) ("threshold=" + $d.threshold)

Write-Host "`n=== 3. warning payload is self-consistent (data driven) ===" -ForegroundColor Cyan
# failedCredits must equal the sum of the listed course credits, and the count must match the list
$sum = 0.0
foreach ($c in @($d.courses)) { $sum += [double]$c.credit }
Check 'failedCredits == sum(course credits)' ([Math]::Abs($sum - [double]$d.failedCredits) -lt 0.001) `
  ("sum=" + $sum + " failedCredits=" + $d.failedCredits)
Check 'failedCourseCount == courses.Count' ([int]$d.failedCourseCount -eq @($d.courses).Count) `
  ("count=" + $d.failedCourseCount + " list=" + @($d.courses).Count)
$reached = ([double]$d.failedCredits -ge [double]$d.threshold)
Check 'warned matches (failedCredits >= threshold)' ([bool]$d.warned -eq $reached) `
  ("warned=" + $d.warned + " reached=" + $reached)

Write-Host "`n=== 4. each student only sees own data ===" -ForegroundColor Cyan
$r2 = Api 'GET' '/academic-warning/my' $student2 $null
$d2 = (ConvertFrom-Json $r2.body).data
Check 'student2 also gets a valid payload' ($null -ne $d2.warned) ("body=" + $r2.body.Substring(0, [Math]::Min(160, $r2.body.Length)))
Check 'payload exposes no student id (id always comes from the token)' `
  (($null -eq $d.studentId) -and ($null -eq $d2.studentId)) 'payload must not carry a studentId field'

Write-Host "`n=== 5. mark-as-read is idempotent and silences the popup ===" -ForegroundColor Cyan
$r = Api 'POST' '/academic-warning/my/read' $student $null
Check 'POST read -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status)
Check 'read returns recorded flag' ($r.body -match '"recorded"') ("body=" + $r.body)
$after = (ConvertFrom-Json (Api 'GET' '/academic-warning/my' $student $null).body).data
Check 'after read: shouldNotify is false' ([bool]$after.shouldNotify -eq $false) ("shouldNotify=" + $after.shouldNotify)
Check 'after read: warned still reports the fact' ([bool]$after.warned -eq [bool]$d.warned) `
  ("before=" + $d.warned + " after=" + $after.warned)
$r = Api 'POST' '/academic-warning/my/read' $student $null
Check 'repeat read is still HTTP 200 (idempotent, no duplicate row)' ($r.status -eq 200) ("status=" + $r.status)

Write-Host "`n=== 6. no per-student read surface ===" -ForegroundColor Cyan
$r = Api 'GET' '/academic-warning/my' $admin $null
Check 'admin GET -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status)
# NOTE: assert on the BUSINESS code, not the HTTP status. This app answers unknown paths with
# HTTP 200 + {"code":"500",...} (a known repo quirk), so "status -ne 200" would be meaningless.
# A real per-student endpoint would answer code "200"; an unmapped one cannot.
$probe = Api 'GET' '/academic-warning/2023002' $admin $null
Check 'no /academic-warning/{studentId} endpoint' ($probe.code -ne '200') `
  ("code=" + $probe.code + " body=" + $probe.body.Substring(0, [Math]::Min(120, $probe.body.Length)))

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
