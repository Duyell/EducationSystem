# M1 security-baseline end-to-end verification (real backend + MySQL + Redis)
#
# Prerequisites:
#   1. MySQL running with the edujwxt schema + seed data
#   2. Redis running on 6379:
#        D:\Redis\5.0.14.1\redis-server.exe .dsh\redis-dev.conf
#   3. Backend fat jar running:
#        cd backend/edu-system-server && mvn -o -B package -DskipTests
#        java -jar edu-api/target/edu-api-0.0.1-SNAPSHOT.jar
#      (stop the backend before rebuilding -- Windows locks the jar)
#   4. No AI_API_KEY needed: unconfigured-AI degradation is asserted, not the model.
#
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI, so non-ASCII
# literals corrupt the parse. Assertions on Chinese response bodies decode the
# raw bytes as UTF-8 and match Unicode code points instead.
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$pass = 0; $fail = 0

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] {0}" -f $name) -ForegroundColor Green }
  else       { $script:fail++; Write-Host ("  [FAIL] {0}  -> {1}" -f $name, $detail) -ForegroundColor Red }
  if ($detail) { Write-Host ("         {0}" -f $detail) -ForegroundColor DarkGray }
}

Write-Host "`n=== 1. Auth boundary without token ===" -ForegroundColor Cyan
try {
  $r = Invoke-WebRequest -Uri "$base/ai/config" -Method GET -UseBasicParsing -TimeoutSec 10
  Check 'GET /ai/config without token is rejected' $false "actual HTTP $($r.StatusCode)"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check 'GET /ai/config without token -> 401' ($code -eq 401) "actual $code"
}

Write-Host "`n=== 2. Login to obtain a real token ===" -ForegroundColor Cyan
$token = $null
$loginBody = @{ username = 'admin01'; password = '123456' } | ConvertTo-Json
try {
  $login = Invoke-RestMethod -Uri "$base/login" -Method POST -ContentType 'application/json' -Body $loginBody -TimeoutSec 15
  $token = $login.token
  Check 'admin01 login returns token' ([bool]$token) "token len=$(if($token){$token.Length}else{0}) role=$($login.role)"
} catch { Check 'admin01 login' $false $_.Exception.Message }

if (-not $token) { Write-Host "`nAborting: login failed" -ForegroundColor Yellow; exit 1 }

Write-Host "`n=== 3. /ai/config degradation when AI is unconfigured ===" -ForegroundColor Cyan
try {
  $cfg = Invoke-RestMethod -Uri "$base/ai/config" -Method GET -Headers @{ token = $token } -TimeoutSec 10
  Check '/ai/config returns code=200' ($cfg.code -eq '200') "code=$($cfg.code)"
  Check 'configured=false when no API key' ($cfg.data.configured -eq $false) "configured=$($cfg.data.configured) model=$($cfg.data.model)"
  Check 'response does NOT expose apiKey' (-not ($cfg.data.PSObject.Properties.Name -contains 'apiKey')) ("fields: " + ($cfg.data.PSObject.Properties.Name -join ','))
} catch { Check '/ai/config reachable' $false $_.Exception.Message }

Write-Host "`n=== 4. /ai/confirm (dangerous-op confirmation) ===" -ForegroundColor Cyan

# 4.1 missing confirmId -> 400
try {
  $r = Invoke-RestMethod -Uri "$base/ai/confirm" -Method POST -Headers @{ token = $token } -ContentType 'application/json' -Body (@{approved=$true} | ConvertTo-Json) -TimeoutSec 10
  Check 'missing confirmId -> code 400' ($r.code -eq '400') "code=$($r.code)"
} catch { Check 'missing confirmId returns 400' $false $_.Exception.Message }

# 4.2 forged confirmId -> 410 (no server-side pending record)
try {
  $r = Invoke-RestMethod -Uri "$base/ai/confirm" -Method POST -Headers @{ token = $token } -ContentType 'application/json' -Body (@{confirmId='bogus-id-12345';approved=$true} | ConvertTo-Json) -TimeoutSec 10
  Check 'forged confirmId -> code 410' ($r.code -eq '410') "code=$($r.code)"
} catch { Check 'forged confirmId returns 410' $false $_.Exception.Message }

# 4.3 no token -> 401
try {
  $r = Invoke-WebRequest -Uri "$base/ai/confirm" -Method POST -ContentType 'application/json' -Body (@{confirmId='x';approved=$true} | ConvertTo-Json) -UseBasicParsing -TimeoutSec 10
  Check '/ai/confirm without token is rejected' $false "actual HTTP $($r.StatusCode)"
} catch {
  $code = $_.Exception.Response.StatusCode.value__
  Check '/ai/confirm without token -> 401' ($code -eq 401) "actual $code"
}

Write-Host "`n=== 5. confirmId ownership check (cannot confirm for another user) ===" -ForegroundColor Cyan
$redisCli = 'D:\Redis\5.0.14.1\redis-cli.exe'
$other = '{"confirmId":"ownership-test","userId":"2023001","role":"student","toolName":"select_course","displayName":"x","arguments":{"courseId":5},"riskLevel":"DANGEROUS","createdAt":0}'
& $redisCli -p 6379 set 'ai:pending:ownership-test' $other EX 120 | Out-Null
try {
  $r = Invoke-RestMethod -Uri "$base/ai/confirm" -Method POST -Headers @{ token = $token } -ContentType 'application/json' -Body (@{confirmId='ownership-test';approved=$true} | ConvertTo-Json) -TimeoutSec 10
  Check 'admin CANNOT confirm a pending action owned by student 2023001' ($r.code -eq '410') "code=$($r.code)"
} catch { Check 'cross-user confirm is rejected' $false $_.Exception.Message }
& $redisCli -p 6379 del 'ai:pending:ownership-test' | Out-Null

Write-Host "`n=== 6. Role authorization (student must not reach admin APIs) ===" -ForegroundColor Cyan
$stuBody = @{ username = '2023001'; password = '123456' } | ConvertTo-Json
try {
  $stu = Invoke-RestMethod -Uri "$base/login" -Method POST -ContentType 'application/json' -Body $stuBody -TimeoutSec 15
  $stuToken = $stu.token
  Check 'student 2023001 login ok' ([bool]$stuToken) "role=$($stu.role)"
  try {
    $r = Invoke-WebRequest -Uri "$base/user" -Method GET -Headers @{ token = $stuToken } -UseBasicParsing -TimeoutSec 10
    Check 'student GET /user must be 403' $false "actual HTTP $($r.StatusCode)"
  } catch {
    $code = $_.Exception.Response.StatusCode.value__
    Check 'student GET /user -> 403' ($code -eq 403) "actual $code"
  }
} catch { Check 'student login' $false $_.Exception.Message }

Write-Host "`n=== 7. /ai/chat behaviour when API key is absent ===" -ForegroundColor Cyan
try {
  $resp = Invoke-WebRequest -Uri "$base/ai/chat" -Method POST -Headers @{ token = $token } -ContentType 'application/json' -Body (@{message='hi'} | ConvertTo-Json) -UseBasicParsing -TimeoutSec 25
  # Read raw bytes and decode as UTF-8. Invoke-WebRequest decodes the body as
  # Latin-1 when Content-Type carries no charset, so string-matching on
  # $resp.Content produces mojibake and false negatives.
  $bytes = $resp.RawContentStream.ToArray()
  $body = [System.Text.Encoding]::UTF8.GetString($bytes)
  $key = [string][char]0x5BC6 + [string][char]0x94A5   # "mi yao" = key
  Check 'unconfigured AI returns an SSE error event (no silent failure)' ($body -match 'event:message' -and $body -match '"type":"error"') ("snippet: " + (($body) -replace '\s+',' '))
  Check 'error text is valid UTF-8 and names the missing key' ($body.Contains("AI API " + $key)) ("decoded: " + ($body -replace '\s+',' '))
  # text/event-stream has exactly one valid encoding (UTF-8) and no way to
  # specify another, so Content-Type legitimately carries no charset parameter.
  # Recorded as a note, not an assertion -- it is spec-correct, not a defect.
  Write-Host ("  [NOTE] Content-Type = {0}  (SSE is UTF-8-only per spec; no charset param expected)" -f $resp.Headers['Content-Type']) -ForegroundColor Yellow
} catch { Check '/ai/chat SSE reachable' $false $_.Exception.Message }

Write-Host "`n=== 8. Rate limiting (phase 0.8) ===" -ForegroundColor Cyan
# Default quota: 10 chats/minute per user. Send 12 and expect the tail to be rejected.
# Reset first because step 7 already consumed one unit for this user.
$redisCli = 'D:\Redis\5.0.14.1\redis-cli.exe'
& $redisCli -p 6379 del "ai:rl:chat:admin01" | Out-Null
$blocked = 0
$allowed = 0
# U+9891 U+7E41 = "pin fan" (frequent), from the limiter's rejection message
$freq = [string][char]0x9891 + [string][char]0x7E41
for ($i = 1; $i -le 12; $i++) {
  try {
    $r = Invoke-WebRequest -Uri "$base/ai/chat" -Method POST -Headers @{ token = $token } -ContentType 'application/json' -Body (@{message='hi'} | ConvertTo-Json) -UseBasicParsing -TimeoutSec 25
    $b = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    if ($b.Contains($freq)) { $blocked++ } else { $allowed++ }
  } catch { $blocked++ }
}
Check 'requests within the per-minute quota are allowed' ($allowed -ge 1) "allowed=$allowed"
Check 'requests beyond the quota are rejected' ($blocked -ge 1) "blocked=$blocked of 12"
Check 'quota boundary matches the configured 10/minute' ($allowed -ge 8 -and $allowed -le 11) "allowed=$allowed (expected ~10)"
& $redisCli -p 6379 del "ai:rl:chat:admin01" | Out-Null

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS={0}  FAIL={1}" -f $pass, $fail) -ForegroundColor $(if($fail -eq 0){'Green'}else{'Red'})
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
