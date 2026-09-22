# Fixture reset for the academic-warning UI verification (repeatability helper).
#
# WHY THIS EXISTS
#   The warning popup is stateful BY DESIGN: after the student acknowledges it, a watermark row
#   is written and the popup must NOT appear again until the situation gets worse. That makes
#   "the popup appears" a ONE-SHOT assertion -- on a second run the watermark is already there
#   and the assertion would fail for a legitimate reason. An unrepeatable check is worse than
#   none, so the runbook is:
#
#     .\.dsh\reset-academic-warning.ps1      # clean fixture (this file)
#     node .dsh\verify-warning-ui.cjs        # seed + assert (seeds its own failing grades)
#     .\.dsh\reset-academic-warning.ps1      # leave the DB as you found it
#
#   Deliberately a TEST fixture, not product surface: it touches ONLY the demo student 2024002
#   and only the two courses the UI script seeds (1 = CS101, 10 = CS107). No API exposes
#   "reset a warning", and none should until a real requirement asks for it.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI).
param(
  [string]$StudentId = '2024002',
  [int[]]$CourseIds = @(1, 10),
  [string]$Mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe',
  [string]$Db = 'edujwxt'
)

$ErrorActionPreference = 'Continue'

function Sql([string]$sql) {
  $out = & $Mysql -uroot -p123456 -D $Db -N -B -e $sql 2>$null
  return @($out | Where-Object { $_ -ne $null -and "$_".Trim() -ne '' })
}

$courseList = ($CourseIds -join ',')
Write-Host ("resetting academic-warning fixture: student=" + $StudentId + " courses=" + $courseList) -ForegroundColor Cyan

# 1. the failing grades seeded by the UI script
$before = (Sql ("select count(*) from score where student_id='" + $StudentId + "' and course_id in (" + $courseList + ")") ) | Select-Object -First 1
Sql ("delete from score where student_id='" + $StudentId + "' and course_id in (" + $courseList + ")") | Out-Null
Write-Host ("  score rows removed : " + $before) -ForegroundColor DarkGray

# 2. the read-watermark rows (this is what makes the popup one-shot)
$wBefore = (Sql ("select count(*) from academic_warning where student_id='" + $StudentId + "'")) | Select-Object -First 1
Sql ("delete from academic_warning where student_id='" + $StudentId + "'") | Out-Null
Write-Host ("  watermark rows removed : " + $wBefore) -ForegroundColor DarkGray

# 3. verify the fixture is clean
$scoreLeft = (Sql ("select count(*) from score where student_id='" + $StudentId + "' and course_id in (" + $courseList + ")") ) | Select-Object -First 1
$warnLeft = (Sql ("select count(*) from academic_warning where student_id='" + $StudentId + "'")) | Select-Object -First 1
if ("$scoreLeft" -eq '0' -and "$warnLeft" -eq '0') {
  Write-Host "fixture clean: ready to run .dsh/verify-warning-ui.cjs" -ForegroundColor Green
  exit 0
}
Write-Host ("fixture NOT clean: scores=" + $scoreLeft + " watermarks=" + $warnLeft) -ForegroundColor Red
exit 1
