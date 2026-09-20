# P5 audit cross-check: what the golden set left behind in ai_tool_audit.
#
# WHY THIS IS A SEPARATE POWERSHELL SCRIPT
#   `.dsh/eval-p5-tools.cjs` proves which tool the model chose by reading the live SSE stream.
#   This script proves the same runs were PERSISTED correctly -- a different claim, and the one
#   that matters for traceability. It has to run from PowerShell because the DSH sandbox denies
#   child processes a pipe (`spawnSync ... EPERM`), so the eval script cannot shell out to the
#   mysql client at all; PowerShell's own pipelines are unaffected.
#
# WHY THERE IS A WATERMARK
#   A time window alone is too coarse to be trustworthy: the unit-test suite writes REAL rows to
#   ai_tool_audit (AiAuditServiceTest, under random `audit-<uuid>` users, including DANGEROUS
#   select_course/enter_score SUCCESS rows). A window-based run therefore reports "a dangerous
#   tool succeeded!" for rows that have nothing to do with the golden set -- a check failing for
#   the wrong reason, which is exactly the kind of assertion this repo keeps getting bitten by.
#   So the run is bracketed by an id watermark instead:
#
#     .\.dsh\verify-p5-audit.ps1 -Mark      # 1. record max(id) BEFORE the eval
#     node .dsh\eval-p5-tools.cjs            # 2. run the golden set
#     .\.dsh\verify-p5-audit.ps1             # 3. check only rows written after the mark
#
#   Run the unit-test suite BEFORE -Mark (see the runbook), then everything after the mark is the
#   golden set's own doing.
#
# WHAT IT ASSERTS (rows after the watermark):
#   1. the golden set actually wrote audit rows (a green eval over an empty table is worthless);
#   2. every read-only student tool the eval asks about is recorded as SUCCESS;
#   3. the two DANGEROUS tools are recorded REJECTED_BY_USER with a confirm_id -- i.e. the run
#      stopped at the card and the refusal was persisted, not just left out;
#   4. NO row in the run is a non-READ_ONLY success (nothing risky slipped through);
#   5. no tool run FAILED or was rejected for INVALID_ARGUMENTS (a model that picked the right
#      tool but sent unusable arguments is a real defect this must not paper over);
#   5b. no SUCCESS row carries an error payload -- this is the check that actually caught one:
#      `check_time_conflict` came back SUCCESS with an error payload saying the course does not
#      exist, because the model *guessed* a course id. The status alone was happy; the user
#      got nothing. (Kept in English on purpose: this file must stay ASCII-only.)
#   6. the callers are only the golden-set accounts (so the rows provably belong to this run);
#   7. rows are well-formed: known status, non-empty tool name and caller.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI).
param(
  [int]$Minutes = 240,
  [int]$SinceId = -1,
  [switch]$Mark,
  [string]$WatermarkFile = '.dsh\p5-audit-watermark.txt',
  [string]$Mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe',
  [string]$Db = 'edujwxt'
)

$ErrorActionPreference = 'Continue'
$pass = 0; $fail = 0

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; Write-Host ("  [PASS] " + $name) -ForegroundColor Green }
  else { $script:fail++; Write-Host ("  [FAIL] " + $name + "  -> " + $detail) -ForegroundColor Red }
}

# Tab-separated, no column headers, one row per line. mysql exits 1 after its
# "password on the command line" warning, so the exit code is deliberately not checked --
# the row count is the real evidence.
function Query([string]$sql) {
  $out = & $Mysql -uroot -p123456 -D $Db -N -B -e $sql 2>$null
  return @($out | Where-Object { $_ -ne $null -and "$_".Trim() -ne '' })
}

if ($Mark) {
  $max = (Query 'select coalesce(max(id),0) from ai_tool_audit') | Select-Object -First 1
  $max = ("$max").Trim()
  if ($max -eq '') { $max = '0' }
  Set-Content -Path $WatermarkFile -Value $max -Encoding ASCII
  Write-Host ("watermark written: " + $WatermarkFile + " = " + $max) -ForegroundColor Cyan
  exit 0
}

$watermark = $null
if ($SinceId -ge 0) {
  $watermark = $SinceId
} elseif (Test-Path $WatermarkFile) {
  $watermark = [int](Get-Content $WatermarkFile -Raw).Trim()
}

if ($watermark -ne $null) {
  Write-Host ("`n=== ai_tool_audit rows with id > " + $watermark + " (golden-set run) ===") -ForegroundColor Cyan
  $where = "id > $watermark"
} else {
  Write-Host ("`n=== ai_tool_audit rows from the last $Minutes minutes (no watermark found) ===") -ForegroundColor Cyan
  Write-Host "  hint: run with -Mark before the eval for a trustworthy bracket" -ForegroundColor DarkGray
  $where = "created_at > date_sub(now(), interval $Minutes minute)"
}

$rows = Query ("select id, user_id, role, tool_name, risk_level, status, ifnull(confirm_id,''), " +
               "ifnull(result_json,'') " +
               "from ai_tool_audit where $where order by id")
$parsed = @()
foreach ($line in $rows) {
  $f = "$line".Split("`t")
  if ($f.Count -ge 8) {
    $parsed += [pscustomobject]@{
      id = [int]$f[0]; user = $f[1]; role = $f[2]; tool = $f[3]
      risk = $f[4]; status = $f[5]; confirmId = $f[6]; result = $f[7]
    }
  }
}

Check "audit rows were written (golden set really ran)" ($parsed.Count -gt 0) ("rows=" + $parsed.Count)
if ($parsed.Count -eq 0) {
  Write-Host "`n========================================" -ForegroundColor Cyan
  Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor Red
  Write-Host "========================================" -ForegroundColor Cyan
  exit 1
}
Write-Host ("  rows=" + $parsed.Count + "  users=" + (($parsed | Select-Object -ExpandProperty user -Unique) -join ',')) -ForegroundColor DarkGray

# ---- 1b. the rows must belong to the golden-set accounts ----
# Without this, "no dangerous tool succeeded" could be satisfied by rows from something else.
Write-Host "`n=== the rows belong to the golden-set accounts ===" -ForegroundColor Cyan
$goldenUsers = @('2023001', '10001', 'admin01')
$strangers = @($parsed | Where-Object { $goldenUsers -notcontains $_.user } | Select-Object -ExpandProperty user -Unique)
Check 'only golden-set callers appear' ($strangers.Count -eq 0) ($strangers -join ',')

# ---- 2. the read-only student tools the golden set exercises must be recorded as SUCCESS ----
# One per golden-set question (audit_my_graduation and get_my_training_plan are alternatives for
# the same question, so either satisfies it).
Write-Host "`n=== read-only student tools recorded as SUCCESS ===" -ForegroundColor Cyan
$studentReadOnly = @{
  'audit_my_graduation|get_my_training_plan' = 'credits/graduation question';
  'get_my_gpa'                               = 'gpa question';
  'recommend_courses'                        = 'course recommendation question';
  'get_my_exams'                             = 'exam question';
  'get_selection_status'                     = 'selection window question';
  'list_my_class_times'                      = 'timetable question';
  'check_time_conflict'                      = 'conflict question'
}
foreach ($key in $studentReadOnly.Keys) {
  $names = $key.Split('|')
  $hit = @($parsed | Where-Object { $names -contains $_.tool -and $_.status -eq 'SUCCESS' })
  Check ("SUCCESS row for " + $key + " (" + $studentReadOnly[$key] + ")") ($hit.Count -gt 0) `
    ("statuses=" + ((@($parsed | Where-Object { $names -contains $_.tool } | Select-Object -ExpandProperty status) -join ',')))
}

# ---- 3. the HITL refusals must be persisted with their confirm token ----
Write-Host "`n=== DANGEROUS tools: refused, persisted, never executed ===" -ForegroundColor Cyan
foreach ($tool in @('submit_course_apply', 'approve_course_apply')) {
  $hit = @($parsed | Where-Object { $_.tool -eq $tool })
  Check ("$tool was attempted at least once") ($hit.Count -gt 0) 'no audit row'
  $rejected = @($hit | Where-Object { $_.status -eq 'REJECTED_BY_USER' -and $_.confirmId -ne '' })
  Check ("$tool recorded REJECTED_BY_USER with a confirm_id") ($rejected.Count -gt 0) `
    ("statuses=" + ((@($hit | Select-Object -ExpandProperty status) -join ',')) + " confirmIds=" + ((@($hit | Select-Object -ExpandProperty confirmId) -join ',')))
  $ran = @($hit | Where-Object { $_.status -eq 'SUCCESS' })
  Check ("$tool was NOT executed before confirmation") ($ran.Count -eq 0) ("success rows=" + $ran.Count)
}

# ---- 4. nothing risky succeeded anywhere in the run ----
Write-Host "`n=== no write/dangerous success in the run ===" -ForegroundColor Cyan
$riskySuccess = @($parsed | Where-Object { $_.risk -ne 'READ_ONLY' -and $_.status -eq 'SUCCESS' })
Check 'no non-READ_ONLY row is SUCCESS' ($riskySuccess.Count -eq 0) `
  (($riskySuccess | ForEach-Object { $_.tool + '/' + $_.risk }) -join ',')

# ---- 5. the tools that ran did not fail ----
Write-Host "`n=== no failed or malformed tool calls ===" -ForegroundColor Cyan
$failed = @($parsed | Where-Object { $_.status -eq 'FAILED' })
Check 'no FAILED row' ($failed.Count -eq 0) (($failed | ForEach-Object { $_.tool }) -join ',')
$badArgs = @($parsed | Where-Object { $_.status -eq 'INVALID_ARGUMENTS' })
Check 'no INVALID_ARGUMENTS row' ($badArgs.Count -eq 0) (($badArgs | ForEach-Object { $_.tool }) -join ',')
$denied = @($parsed | Where-Object { $_.status -eq 'DENIED' })
Check 'no DENIED (role-escalation) row' ($denied.Count -eq 0) (($denied | ForEach-Object { $_.user + ':' + $_.tool }) -join ',')

# ---- 5b. a SUCCESS row must not actually be an error payload ----
# The tool contract returns business errors INSIDE the payload (so the model can read and correct),
# which means status=SUCCESS alone proves nothing. This is the check that caught the model
# inventing courseId=101 for check_time_conflict: the run looked green until the payload was read.
Write-Host "`n=== SUCCESS rows carry real results, not error payloads ===" -ForegroundColor Cyan
$errorPayloads = @($parsed | Where-Object { $_.status -eq 'SUCCESS' -and $_.result -match '"error"' })
Check 'no SUCCESS row has an error payload' ($errorPayloads.Count -eq 0) `
  (($errorPayloads | ForEach-Object { $_.tool + ' -> ' + $_.result.Substring(0, [Math]::Min(90, $_.result.Length)) }) -join ' | ')
$emptyPayloads = @($parsed | Where-Object { $_.status -eq 'SUCCESS' -and "$($_.result)".Trim() -eq '' })
Check 'every SUCCESS row has a payload' ($emptyPayloads.Count -eq 0) (($emptyPayloads | ForEach-Object { $_.tool }) -join ',')

# ---- 6. data hygiene ----
Write-Host "`n=== audit rows are well-formed ===" -ForegroundColor Cyan
$known = @('SUCCESS', 'FAILED', 'DENIED', 'REJECTED_BY_USER', 'INVALID_ARGUMENTS', 'DUPLICATE_SKIPPED')
$badStatus = @($parsed | Where-Object { $known -notcontains $_.status })
Check 'every status is a known value' ($badStatus.Count -eq 0) (($badStatus | ForEach-Object { $_.status }) -join ',')
$noTool = @($parsed | Where-Object { "$($_.tool)".Trim() -eq '' })
Check 'every row names a tool' ($noTool.Count -eq 0) ("count=" + $noTool.Count)
$noUser = @($parsed | Where-Object { "$($_.user)".Trim() -eq '' })
Check 'every row names a caller' ($noUser.Count -eq 0) ("count=" + $noUser.Count)
$badRole = @($parsed | Where-Object { @('student', 'teacher', 'admin') -notcontains $_.role })
Check 'every role is student/teacher/admin' ($badRole.Count -eq 0) (($badRole | ForEach-Object { $_.role }) -join ',')

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
