# Validate that edujwxt.sql + seed_data.sql import cleanly, in docker-compose order,
# into a THROWAWAY database.
#
# IMPORTANT: both files contain DROP TABLE / unconditional INSERT and have no USE
# clause, so importing them against the live schema would wipe real data. This
# script renames every table to a `v_` prefix in copies, imports the copies into a
# temp database, checks results, then drops the temp database.
#
# ASCII-only on purpose (Windows PowerShell 5.1 reads .ps1 as ANSI).
# NOTE: ErrorActionPreference must stay Continue -- mysql writes its
# "password on the command line" warning to stderr, which PowerShell would
# otherwise treat as a terminating error.
$ErrorActionPreference = 'Continue'
$mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe'
$db = 'edujwxt_validate'
$repo = 'D:\work\jwxt\EducationSystem'

$tables = @('clazz','college','course','course_selection','major','score','student',
            'sys_user','teacher','teacher_evaluation','ai_tool_audit','training_plan',
            'plan_course','gpa_rule','room','class_time','course_apply','class_time_apply')

$sources = @(
  (Join-Path $repo 'edujwxt.sql'),
  (Join-Path $repo 'seed_data.sql')
)

Write-Host '--- preparing prefixed copies ---'
$prepared = @()
foreach ($src in $sources) {
  $text = [System.IO.File]::ReadAllText($src)
  foreach ($t in $tables) { $text = $text.Replace('`' + $t + '`', '`v_' + $t + '`') }
  $out = Join-Path $env:TEMP ('val_' + (Split-Path $src -Leaf))
  [System.IO.File]::WriteAllText($out, $text, (New-Object System.Text.UTF8Encoding($false)))
  $prepared += $out
  Write-Host ("  {0} -> {1}" -f (Split-Path $src -Leaf), $out)
}

Write-Host '--- creating temp database ---'
& $mysql -uroot -p123456 -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db DEFAULT CHARACTER SET utf8mb4;" 2>$null

$fail = 0
foreach ($p in $prepared) {
  Write-Host ("--- importing {0} ---" -f (Split-Path $p -Leaf))
  $out = & $mysql -uroot -p123456 --default-character-set=utf8mb4 $db -e "source $p" 2>&1
  $errors = $out | Where-Object { $_ -match 'ERROR' }
  if ($errors) { $errors | ForEach-Object { "  $_" }; $fail++ }
  else { Write-Host '  ok' }
}

Write-Host '--- row counts in temp db ---'
& $mysql -uroot -p123456 $db -N -e @"
SELECT 'college',COUNT(*) FROM v_college UNION ALL
SELECT 'major',COUNT(*) FROM v_major UNION ALL
SELECT 'clazz',COUNT(*) FROM v_clazz UNION ALL
SELECT 'student',COUNT(*) FROM v_student UNION ALL
SELECT 'teacher',COUNT(*) FROM v_teacher UNION ALL
SELECT 'course',COUNT(*) FROM v_course UNION ALL
SELECT 'score',COUNT(*) FROM v_score UNION ALL
SELECT 'course_selection',COUNT(*) FROM v_course_selection UNION ALL
SELECT 'sys_user',COUNT(*) FROM v_sys_user UNION ALL
SELECT 'training_plan',COUNT(*) FROM v_training_plan UNION ALL
SELECT 'plan_course',COUNT(*) FROM v_plan_course UNION ALL
SELECT 'gpa_rule',COUNT(*) FROM v_gpa_rule UNION ALL
SELECT 'room',COUNT(*) FROM v_room UNION ALL
SELECT 'class_time',COUNT(*) FROM v_class_time UNION ALL
SELECT 'course_apply',COUNT(*) FROM v_course_apply UNION ALL
SELECT 'class_time_apply',COUNT(*) FROM v_class_time_apply;
"@ 2>$null | ForEach-Object { "  $_" }

Write-Host '--- data integrity spot checks ---'
& $mysql -uroot -p123456 $db -N -e @"
SELECT CONCAT('course with null course_code: ', COUNT(*)) FROM v_course WHERE course_code IS NULL OR course_code='';
SELECT CONCAT('score with null passed: ', COUNT(*)) FROM v_score WHERE passed IS NULL;
SELECT CONCAT('score with null makeup_score (expected, only makeup rows have it): ', COUNT(*)) FROM v_score WHERE makeup_score IS NULL;
SELECT CONCAT('student default status ok: ', COUNT(*)) FROM v_student WHERE status='ACTIVE';
SELECT CONCAT('rooms generated (expect 800): ', COUNT(*)) FROM v_room;
SELECT CONCAT('distinct buildings (expect 8): ', COUNT(DISTINCT building)) FROM v_room;
-- ASCII-only script: never write a building name literal here. Assert the shape instead.
SELECT CONCAT('buildings NOT having exactly 100 rooms: ', COUNT(*)) FROM (SELECT building FROM v_room GROUP BY building HAVING COUNT(*) <> 100) t;
SELECT CONCAT('distinct floor/room combinations (expect 100): ', COUNT(*)) FROM (SELECT floor_no, room_no FROM v_room GROUP BY floor_no, room_no) t;
SELECT CONCAT('room with null/blank name: ', COUNT(*)) FROM v_room WHERE room_name IS NULL OR room_name='';
SELECT CONCAT('class_time rows (expect 3): ', COUNT(*)) FROM v_class_time;
SELECT CONCAT('class_time with null course_id: ', COUNT(*)) FROM v_class_time WHERE course_id IS NULL;
SELECT CONCAT('class_time with null room_id: ', COUNT(*)) FROM v_class_time WHERE room_id IS NULL;
"@ 2>$null | ForEach-Object { "  $_" }

Write-Host '--- cleanup ---'
& $mysql -uroot -p123456 -e "DROP DATABASE IF EXISTS $db;" 2>$null
foreach ($p in $prepared) { Remove-Item $p -ErrorAction SilentlyContinue }

if ($fail -gt 0) { Write-Host ("FAILED: {0} file(s) had import errors" -f $fail) }
else { Write-Host 'ALL IMPORTS OK' }
