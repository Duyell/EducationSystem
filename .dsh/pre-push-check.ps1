# Pre-push gate: run the three fast checks locally BEFORE pushing.
#
# WHY THIS EXISTS
#   CI failures send the user a notification, and every check below is runnable locally in a couple
#   of minutes. Three CI failures in this repo's history were avoidable this way:
#     - a spec file that did not survive `vue-tsc --build` (order mistake, see below);
#     - a script that hard-coded this machine's mysql path (only fails on the Linux runner);
#     - a test asserting a status string that was not in AuditStatus.
#   It cannot catch platform differences, but it catches "I forgot to run it".
#
# ORDER MATTERS
#   type-check must run AFTER test files are added/changed: `vue-tsc --build` type-checks the test
#   files too (tsconfig.vitest.json is part of the build), and running it first is exactly how a
#   green-local / red-CI push happened.
#
# WHY EVERY COMMAND GOES THROUGH `cmd /c`
#   Inside a .ps1, `$out = & mvn ...` left `$LASTEXITCODE` EMPTY (not 0) and `$null -ne 0` is true,
#   so the script reported "backend tests failed" while maven had actually succeeded; piping into
#   Select-Object hides the same trap behind a cmdlet. `& cmd /c "..."` sets `$LASTEXITCODE`
#   reliably in both the assignment and pipeline forms (verified), so decisions are made on it.
#
# Usage:  .\.dsh\pre-push-check.ps1            # backend tests are skipped if MySQL/Redis are down
#         .\.dsh\pre-push-check.ps1 -SkipBackend
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI; a stray multibyte character
# can swallow the NEXT line of code -- this file got one slipped in once and it was caught by the
# byte count check, not by the parser).
param(
  [switch]$SkipBackend
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$frontend = Join-Path $root 'frontend\edu-system-client'
$backend = Join-Path $root 'backend\edu-system-server'

$failed = @()
$skipped = @()

function Step($name, [scriptblock]$body) {
  Write-Host ""
  Write-Host ("=== " + $name + " ===") -ForegroundColor Cyan
  & $body
}

# --- 1. backend tests (need MySQL + Redis) ---------------------------------------------------
Step 'backend tests (mvn -o test)' {
  if ($SkipBackend) {
    $script:skipped += 'backend tests (-SkipBackend)'
    Write-Host '  [SKIP] -SkipBackend' -ForegroundColor Yellow
    return
  }

  $mysqlUp = Test-NetConnection -ComputerName localhost -Port 3306 -InformationLevel Quiet -WarningAction SilentlyContinue
  $redisUp = Test-NetConnection -ComputerName localhost -Port 6379 -InformationLevel Quiet -WarningAction SilentlyContinue
  if (-not $mysqlUp -or -not $redisUp) {
    $script:skipped += ("backend tests (MySQL=" + $mysqlUp + ", Redis=" + $redisUp + ")")
    Write-Host ("  [SKIP] needs MySQL(3306) and Redis(6379): MySQL=" + $mysqlUp + " Redis=" + $redisUp) -ForegroundColor Yellow
    return
  }

  Push-Location $backend
  # No -q: Maven prints BUILD SUCCESS / BUILD FAILURE, and on failure the failing tests are visible.
  $out = & cmd /c "mvn -o -B test -pl edu-api -am 2>&1"
  $code = $LASTEXITCODE
  Pop-Location

  if ($code -eq 0) {
    Write-Host '  [PASS] backend tests (321 expected; see the summary line below)' -ForegroundColor Green
    @($out) | Select-String -Pattern 'Tests run: [0-9]+, Failures' | Select-Object -Last 1 | ForEach-Object { Write-Host ('  | ' + $_.Line.Trim()) }
  } else {
    $script:failed += ("backend tests (mvn exit " + $code + ")")
    Write-Host ("  [FAIL] mvn exit " + $code) -ForegroundColor Red
    @($out) | Select-Object -Last 25 | ForEach-Object { Write-Host ('  | ' + $_) }
  }
}

# --- 2. frontend type check (AFTER any spec change) ------------------------------------------
Step 'frontend type check (vue-tsc --build)' {
  Push-Location $frontend
  $out = & cmd /c "npm run type-check 2>&1"
  $code = $LASTEXITCODE
  Pop-Location

  if ($code -eq 0) {
    Write-Host '  [PASS] vue-tsc exit 0 (no output on success)' -ForegroundColor Green
  } else {
    $script:failed += ("frontend type-check (exit " + $code + ")")
    Write-Host ("  [FAIL] vue-tsc exit " + $code) -ForegroundColor Red
    @($out) | Select-Object -Last 20 | ForEach-Object { Write-Host ('  | ' + $_) }
  }
}

# --- 3. frontend unit tests ------------------------------------------------------------------
Step 'frontend unit tests (vitest run)' {
  Push-Location $frontend
  $out = & cmd /c "npx vitest run 2>&1"
  $code = $LASTEXITCODE
  Pop-Location

  $text = (@($out) | Out-String)
  if ($code -eq 0) {
    Write-Host '  [PASS] vitest' -ForegroundColor Green
    @($out) | Select-String -Pattern 'Tests +[0-9]+ passed|Test Files' | Select-Object -Last 2 | ForEach-Object { Write-Host ('  | ' + $_.Line.Trim()) }
  } elseif ($text -match 'spawn EPERM') {
    # The DSH sandbox denies child processes a pipe, and Vite's Windows realpath probe spawns one:
    # vitest dies with "spawn EPERM" before running a single test. That is an environment limit,
    # not a failing test -- reporting it as a failure would be the same "infrastructure masquerading
    # as an assertion" mistake this repo keeps fixing. CI runs it fine.
    $script:skipped += 'frontend unit tests (sandbox blocks the Vite child process; run outside the DSH sandbox)'
    Write-Host '  [SKIP] spawn EPERM: run this outside the DSH sandbox (CI runs it fine)' -ForegroundColor Yellow
  } else {
    $script:failed += ("frontend unit tests (exit " + $code + ")")
    Write-Host ("  [FAIL] vitest exit " + $code) -ForegroundColor Red
    @($out) | Select-Object -Last 25 | ForEach-Object { Write-Host ('  | ' + $_) }
  }
}

Write-Host ""
Write-Host '========================================' -ForegroundColor Cyan
if ($failed.Count -eq 0) { Write-Host 'PRE-PUSH OK' -ForegroundColor Green }
else { Write-Host ('PRE-PUSH FAILED: ' + ($failed -join ', ')) -ForegroundColor Red }
if ($skipped.Count -gt 0) { Write-Host ('skipped: ' + ($skipped -join '; ')) -ForegroundColor Yellow }
Write-Host '========================================' -ForegroundColor Cyan
exit $(if ($failed.Count -eq 0) { 0 } else { 1 })
