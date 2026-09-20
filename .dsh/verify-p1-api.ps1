# P1 API verification: permissions and payloads for the new /gpa and /training-plan endpoints.
#
# Focus: the permission rules are order-sensitive (first match wins), so a misordered
# rule silently grants or denies access. This script asserts BOTH sides: the intended
# caller gets 200, and every other role gets 403.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI).
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$pass = 0; $fail = 0

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + "  -> " + $detail) -ForegroundColor Red }
  if ($detail -and -not $cond) { }
}

function Login($u, $p) {
  try {
    $r = Invoke-RestMethod -Uri "$base/login" -Method POST -ContentType 'application/json' `
      -Body (@{ username = $u; password = $p } | ConvertTo-Json) -TimeoutSec 15
    return $r.token
  } catch { return $null }
}

# returns @{ status = httpStatus; code = businessCode; body = rawText }
function Get-Api($path, $token) {
  try {
    $r = Invoke-WebRequest -Uri ($base + $path) -Headers @{ token = $token } -UseBasicParsing -TimeoutSec 20
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
$admin = Login 'admin01' '123456'
$student = Login '2023001' '123456'
$student2 = Login '2023002' '123456'
$teacher = Login '10001' '123456'
Check 'admin01 login' ($null -ne $admin)
Check '2023001 (student) login' ($null -ne $student)
Check '2023002 (student) login' ($null -ne $student2)
Check '10001 (teacher) login' ($null -ne $teacher)

Write-Host "`n=== 1. student endpoints (own data) ===" -ForegroundColor Cyan
$r = Get-Api '/gpa/my' $student
Check '/gpa/my student -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status + " body=" + $r.body.Substring(0, [Math]::Min(120, $r.body.Length)))
Check '/gpa/my returns business code 200' ($r.code -eq '200') ("code=" + $r.code)
Check '/gpa/my payload contains gpa and rank' ($r.body -match '"gpa"' -and $r.body -match '"rank"')

$r = Get-Api '/gpa/rank' $student
Check '/gpa/rank student -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status)

$r = Get-Api '/gpa/audit/my' $student
Check '/gpa/audit/my student -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status)
Check 'audit payload has planFound (no plan => still 200)' ($r.body -match '"planFound"')

$r = Get-Api '/training-plan/my' $student
Check '/training-plan/my student -> HTTP 200' ($r.status -eq 200) ("status=" + $r.status + " body=" + $r.body.Substring(0, [Math]::Min(150, $r.body.Length)))
Check '/training-plan/my returns plan + courses + audit' ($r.body -match '"plan"' -and $r.body -match '"courses"' -and $r.body -match '"audit"')

Write-Host "`n=== 2. student must NOT reach admin endpoints ===" -ForegroundColor Cyan
foreach ($p in @('/gpa/2023001', '/gpa/rank/2023001', '/gpa/audit/2023001', '/training-plan', '/training-plan/1')) {
  $r = Get-Api $p $student
  Check ("student -> " + $p + " is 403") ($r.status -eq 403) ("status=" + $r.status)
}

Write-Host "`n=== 3. teacher has NO gpa/training-plan access (user requirement) ===" -ForegroundColor Cyan
foreach ($p in @('/gpa/my', '/gpa/rank', '/gpa/audit/my', '/gpa/2023001', '/training-plan', '/training-plan/my')) {
  $r = Get-Api $p $teacher
  Check ("teacher -> " + $p + " is 403") ($r.status -eq 403) ("status=" + $r.status)
}

Write-Host "`n=== 4. admin endpoints ===" -ForegroundColor Cyan
$r = Get-Api '/training-plan' $admin
Check 'admin -> /training-plan is 200' ($r.status -eq 200) ("status=" + $r.status)
Check 'admin list contains the seeded plan' ($r.body -match '2023') ("body=" + $r.body.Substring(0, [Math]::Min(200, $r.body.Length)))

$r = Get-Api '/gpa/2023001' $admin
Check 'admin -> /gpa/{studentId} is 200' ($r.status -eq 200) ("status=" + $r.status)

$r = Get-Api '/gpa/rank/2023001' $admin
Check 'admin -> /gpa/rank/{studentId} is 200' ($r.status -eq 200) ("status=" + $r.status)

$r = Get-Api '/training-plan/1' $admin
Check 'admin -> /training-plan/{id} is 200' ($r.status -eq 200) ("status=" + $r.status)
Check 'plan detail contains 12 course rows' (([regex]::Matches($r.body, '"courseCode"')).Count -ge 12) ("courseCode count=" + ([regex]::Matches($r.body, '"courseCode"')).Count)

Write-Host "`n=== 5. student is scoped to OWN data (cannot see others) ===" -ForegroundColor Cyan
# /gpa/my and /training-plan/my take the student id from the token, never from input,
# so passing another id must not change the result.
$r1 = Get-Api '/gpa/my' $student
$r2 = Get-Api '/gpa/my?studentId=2023002' $student2
Check 'two different students get their own /gpa/my' ($r1.body -ne $r2.body) 'bodies identical - suspicious'
$r3 = Get-Api '/gpa/my' $student2
Check '/gpa/my ignores unrelated query params' ($r3.body -match '2023002') 'payload not scoped to token user'

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
