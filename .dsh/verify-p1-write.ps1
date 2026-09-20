# P1 write-path verification: the MUTATING /training-plan endpoints.
#
# verify-p1-api.ps1 covers the read paths and role denials for GETs. This script
# covers what the training-plan-manage page actually calls: create / update a plan,
# add / remove a plan course -- including the denials (anonymous, student, teacher)
# and the service-level validation that must reject bad or duplicate input.
#
# It is re-runnable: it wipes its own fixture (grade '2099') before AND after, so a
# crashed earlier run cannot poison this one or leave residue behind.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses .ps1 as ANSI, and Chinese
# literals in this file previously broke parsing outright).
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$mysql = 'D:\mysql-8.4.7-winx64\mysql-8.4.7-winx64\bin\mysql.exe'
$pass = 0; $fail = 0

# Fixture identity. grade 2099 is deliberately impossible in real data, and major 2
# has no seeded plan, so the fixture cannot collide with the sample plan (major 1 / 2023).
$TEST_GRADE = '2099'
$TEST_MAJOR = 2
$SEEDED_PLAN_ID = 1

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
  # No FK between the two tables, but delete children first anyway.
  & $mysql -uroot -p123456 -D edujwxt -N -B -e "delete from plan_course where plan_id in (select id from training_plan where grade='$TEST_GRADE'); delete from training_plan where grade='$TEST_GRADE';" 2>&1 | Out-Null
}

function ResidueCount {
  $n = & $mysql -uroot -p123456 -D edujwxt -N -B -e "select (select count(*) from training_plan where grade='$TEST_GRADE') + (select count(*) from plan_course pc left join training_plan tp on pc.plan_id = tp.id where tp.grade='$TEST_GRADE');" 2>$null
  return [int]($n | Select-Object -First 1)
}

# Invoke any method with optional token/body. Returns status + unwrapped business code/msg/data.
# 4xx/5xx are returned as values, not thrown, because the denials ARE the assertions.
function Api($method, $path, $token, $body) {
  $headers = @{}
  if ($token) { $headers['token'] = $token }
  $p = @{ Uri = ($base + $path); Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 25 }
  if ($null -ne $body) {
    $p['ContentType'] = 'application/json'
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
    json   = $json
    body   = $txt
  }
}

function Brief($r) { return ("status=" + $r.status + " code=" + $r.code + " body=" + $r.body.Substring(0, [Math]::Min(160, $r.body.Length))) }

Write-Host "`n=== 0. fixture cleanup + logins ===" -ForegroundColor Cyan
Cleanup
Check 'no residue before the run' ((ResidueCount) -eq 0) ("residue=" + (ResidueCount))

$admin = Login 'admin01' '123456'
$student = Login '2023001' '123456'
$teacher = Login '10001' '123456'
Check 'admin01 login' ($null -ne $admin)
Check '2023001 (student) login' ($null -ne $student)
Check '10001 (teacher) login' ($null -ne $teacher)

$validBody = @{
  planName         = 'VERIFY automated plan'
  majorId          = $TEST_MAJOR
  grade            = $TEST_GRADE
  totalCredits     = 10.5
  requiredCredits  = 4.0
  electiveCredits  = 6.5
  status           = 1
  remark           = 'created by .dsh/verify-p1-write.ps1'
}

Write-Host "`n=== 1. anonymous must be rejected (401) on every mutating route ===" -ForegroundColor Cyan
$r = Api 'POST' '/training-plan' $null $validBody
Check 'anon POST /training-plan -> 401' ($r.status -eq 401) (Brief $r)
$r = Api 'PUT' '/training-plan' $null $validBody
Check 'anon PUT /training-plan -> 401' ($r.status -eq 401) (Brief $r)
$r = Api 'POST' '/training-plan/course' $null @{ planId = 1; courseCode = 'X'; category = 'REQUIRED' }
Check 'anon POST /training-plan/course -> 401' ($r.status -eq 401) (Brief $r)
$r = Api 'DELETE' '/training-plan/course/1' $null $null
Check 'anon DELETE /training-plan/course/{id} -> 401' ($r.status -eq 401) (Brief $r)

Write-Host "`n=== 2. student and teacher must NOT be able to write (403) ===" -ForegroundColor Cyan
foreach ($who in @(@('student', $student), @('teacher', $teacher))) {
  $label = $who[0]; $tok = $who[1]
  $r = Api 'POST' '/training-plan' $tok $validBody
  Check ($label + " POST /training-plan -> 403") ($r.status -eq 403) (Brief $r)
  $r = Api 'PUT' '/training-plan' $tok $validBody
  Check ($label + " PUT /training-plan -> 403") ($r.status -eq 403) (Brief $r)
  $r = Api 'POST' '/training-plan/course' $tok @{ planId = 1; courseCode = 'X'; category = 'REQUIRED' }
  Check ($label + " POST /training-plan/course -> 403") ($r.status -eq 403) (Brief $r)
  $r = Api 'DELETE' '/training-plan/course/1' $tok $null
  Check ($label + " DELETE /training-plan/course/{id} -> 403") ($r.status -eq 403) (Brief $r)
}
# The seeded plan must be untouched by the denied writes above.
$r = Api 'GET' "/training-plan/$SEEDED_PLAN_ID" $admin $null
$seededCourseCount = @($r.data.courses).Count
Check 'seeded plan still has 12 course rows after denied writes' ($seededCourseCount -eq 12) ("count=" + $seededCourseCount)

Write-Host "`n=== 3. create validation (admin) ===" -ForegroundColor Cyan
$bad = $validBody.Clone(); $bad.Remove('planName')
$r = Api 'POST' '/training-plan' $admin $bad
Check 'create without planName -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$bad = $validBody.Clone(); $bad.Remove('grade')
$r = Api 'POST' '/training-plan' $admin $bad
Check 'create without grade -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$bad = $validBody.Clone(); $bad.Remove('majorId')
$r = Api 'POST' '/training-plan' $admin $bad
Check 'create without majorId -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$bad = $validBody.Clone(); $bad['planName'] = '   '
$r = Api 'POST' '/training-plan' $admin $bad
Check 'create with blank-only planName -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

Check 'no plan was created by the rejected payloads' ((ResidueCount) -eq 0) ("residue=" + (ResidueCount))

Write-Host "`n=== 4. create succeeds and returns the new id ===" -ForegroundColor Cyan
$r = Api 'POST' '/training-plan' $admin $validBody
Check 'admin POST /training-plan -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$newId = 0
try { $newId = [int]$r.data } catch { $newId = 0 }
Check 'response data is the new plan id' ($newId -gt $SEEDED_PLAN_ID) ("id=" + $newId)

Write-Host "`n=== 5. duplicate (major+grade) create is rejected ===" -ForegroundColor Cyan
$r = Api 'POST' '/training-plan' $admin $validBody
Check 'duplicate POST /training-plan -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)
Check 'still exactly one plan for the fixture' ((ResidueCount) -eq 1) ("residue=" + (ResidueCount))

Write-Host "`n=== 6. list filters ===" -ForegroundColor Cyan
$r = Api 'GET' ("/training-plan?majorId=" + $TEST_MAJOR + "&grade=" + $TEST_GRADE) $admin $null
Check 'filter by majorId+grade returns just the fixture' (@($r.data).Count -eq 1) ("count=" + @($r.data).Count + " " + $r.body.Substring(0, [Math]::Min(160, $r.body.Length)))
Check 'filtered row carries its majorName (join works)' ($r.data[0].majorName) ("majorName=" + $r.data[0].majorName)

$r = Api 'GET' ("/training-plan?grade=" + $TEST_GRADE) $admin $null
Check 'filter by grade alone finds the fixture' (@($r.data).Count -eq 1) ("count=" + @($r.data).Count)

$r = Api 'GET' '/training-plan?majorId=1&grade=2023' $admin $null
Check 'filter finds the seeded plan' (@($r.data).Count -eq 1 -and [int]$r.data[0].id -eq $SEEDED_PLAN_ID) ("count=" + @($r.data).Count)

$r = Api 'GET' '/training-plan?grade=1999' $admin $null
Check 'unmatched grade filter returns empty list' (@($r.data).Count -eq 0) ("count=" + @($r.data).Count + " body=" + $r.body)

Write-Host "`n=== 7. detail of the new plan (no courses yet) ===" -ForegroundColor Cyan
$r = Api 'GET' "/training-plan/$newId" $admin $null
Check 'detail returns the fixture plan' ($r.status -eq 200 -and [int]$r.data.plan.id -eq $newId) (Brief $r)
Check 'detail reports 0 courses' (@($r.data.courses).Count -eq 0) ("count=" + @($r.data.courses).Count)
Check 'detail echoes the credit requirements' ([decimal]$r.data.plan.totalCredits -eq 10.5) ("total=" + $r.data.plan.totalCredits)
$r = Api 'GET' '/training-plan/999999' $admin $null
Check 'detail of unknown id -> business error, not 500' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

Write-Host "`n=== 8. add plan course validation ===" -ForegroundColor Cyan
$courseBody = @{
  planId          = $newId
  courseCode      = 'VERIFY-CODE'
  courseName      = 'VERIFY required course'
  category        = 'REQUIRED'
  suggestSemester = 1
  credit          = 2.5
}
$r = Api 'POST' '/training-plan/course' $admin @{ planId = $newId; courseCode = ''; category = 'REQUIRED'; credit = 1 }
Check 'add with blank courseCode -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$r = Api 'POST' '/training-plan/course' $admin @{ planId = $newId; courseCode = 'VERIFY-NOCAT'; category = 'BOGUS'; credit = 1 }
Check 'add with invalid category -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$r = Api 'POST' '/training-plan/course' $admin @{ courseCode = 'VERIFY-NOPLAN'; category = 'REQUIRED'; credit = 1 }
Check 'add without planId -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$r = Api 'POST' '/training-plan/course' $admin @{ planId = 999999; courseCode = 'VERIFY-GHOST'; category = 'REQUIRED'; credit = 1 }
Check 'add to unknown planId -> business error (no orphan row)' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

Write-Host "`n=== 9. add a REQUIRED course ===" -ForegroundColor Cyan
$r = Api 'POST' '/training-plan/course' $admin $courseBody
Check 'add REQUIRED course -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)

$r = Api 'POST' '/training-plan/course' $admin $courseBody
Check 'adding the same courseCode twice -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$r = Api 'GET' "/training-plan/$newId" $admin $null
$courses = @($r.data.courses)
Check 'detail now reports 1 course' ($courses.Count -eq 1) ("count=" + $courses.Count)
Check 'course round-trips code / category / credit' ($courses[0].courseCode -eq 'VERIFY-CODE' -and $courses[0].category -eq 'REQUIRED' -and [decimal]$courses[0].credit -eq 2.5) ("row=" + ($courses[0] | ConvertTo-Json -Compress))
$requiredCourseId = [int]$courses[0].id

Write-Host "`n=== 10. add an ELECTIVE course (credit is a snapshot) ===" -ForegroundColor Cyan
$r = Api 'POST' '/training-plan/course' $admin @{
  planId = $newId; courseCode = 'VERIFY-CODE-2'; courseName = 'VERIFY elective course'
  category = 'ELECTIVE'; suggestSemester = 2; credit = 1.5
}
Check 'add ELECTIVE course -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'GET' "/training-plan/$newId" $admin $null
$courses = @($r.data.courses)
Check 'detail now reports 2 courses' ($courses.Count -eq 2) ("count=" + $courses.Count)
$elective = @($courses | Where-Object { $_.courseCode -eq 'VERIFY-CODE-2' })[0]
Check 'elective row keeps category ELECTIVE and its own credit' ($elective.category -eq 'ELECTIVE' -and [decimal]$elective.credit -eq 1.5) ("row=" + ($elective | ConvertTo-Json -Compress))

Write-Host "`n=== 11. update the plan ===" -ForegroundColor Cyan
$r = Api 'PUT' '/training-plan' $admin @{ id = 0; planName = 'x'; majorId = $TEST_MAJOR; grade = $TEST_GRADE }
Check 'update without id -> business error' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$r = Api 'PUT' '/training-plan' $admin @{ id = $newId; planName = 'VERIFY renamed'; majorId = 1; grade = '2023' }
Check 'update onto the seeded major+grade -> business error (no silent overwrite)' ($r.status -eq 200 -and $r.code -ne '200') (Brief $r)

$r = Api 'PUT' '/training-plan' $admin @{
  id = $newId; planName = 'VERIFY renamed'; majorId = $TEST_MAJOR; grade = $TEST_GRADE
  totalCredits = 20.0; requiredCredits = 8.0; electiveCredits = 12.0; status = 0; remark = 'updated by verify'
}
Check 'valid update -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)

$r = Api 'GET' "/training-plan/$newId" $admin $null
Check 'update persisted planName' ($r.data.plan.planName -eq 'VERIFY renamed') ("planName=" + $r.data.plan.planName)
Check 'update persisted credit requirements' ([decimal]$r.data.plan.totalCredits -eq 20.0 -and [decimal]$r.data.plan.requiredCredits -eq 8.0 -and [decimal]$r.data.plan.electiveCredits -eq 12.0) ("row=" + ($r.data.plan | ConvertTo-Json -Compress))
Check 'update persisted status=0' ([int]$r.data.plan.status -eq 0) ("status=" + $r.data.plan.status)
Check 'the rejected cross-major update did not move majorId' ([int]$r.data.plan.majorId -eq $TEST_MAJOR) ("majorId=" + $r.data.plan.majorId)
Check 'course rows survived the plan update' (@($r.data.courses).Count -eq 2) ("count=" + @($r.data.courses).Count)

$r = Api 'GET' '/training-plan?majorId=1&grade=2023' $admin $null
Check 'seeded plan untouched by the failed cross-major update' (@($r.data).Count -eq 1 -and $r.data[0].planName -notlike 'VERIFY*') ("planName=" + $r.data[0].planName)

$r = Api 'GET' ("/training-plan?majorId=" + $TEST_MAJOR + "&grade=" + $TEST_GRADE) $admin $null
Check 'admin list still shows the disabled plan (status-agnostic)' (@($r.data).Count -eq 1 -and [int]$r.data[0].status -eq 0) ("count=" + @($r.data).Count)

Write-Host "`n=== 12. remove a plan course ===" -ForegroundColor Cyan
$r = Api 'DELETE' "/training-plan/course/$requiredCourseId" $admin $null
Check 'delete existing course row -> 200' ($r.status -eq 200 -and $r.code -eq '200') (Brief $r)
$r = Api 'GET' "/training-plan/$newId" $admin $null
$left = @($r.data.courses)
Check 'detail now reports 1 course' ($left.Count -eq 1) ("count=" + $left.Count)
Check 'the right row was removed' ($left[0].courseCode -eq 'VERIFY-CODE-2') ("left=" + $left[0].courseCode)

$r = Api 'DELETE' '/training-plan/course/999999' $admin $null
Check 'deleting an unknown course id does not 500' ($r.status -eq 200) (Brief $r)

Write-Host "`n=== 13. cleanup and confirm no residue ===" -ForegroundColor Cyan
Cleanup
Check 'fixture fully removed' ((ResidueCount) -eq 0) ("residue=" + (ResidueCount))
$r = Api 'GET' ("/training-plan?grade=" + $TEST_GRADE) $admin $null
Check 'fixture no longer listed' (@($r.data).Count -eq 0) ("count=" + @($r.data).Count)
$r = Api 'GET' "/training-plan/$SEEDED_PLAN_ID" $admin $null
Check 'seeded plan (12 courses) intact after the whole run' (@($r.data.courses).Count -eq 12) ("count=" + @($r.data.courses).Count)

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host ("RESULT: PASS=" + $pass + "  FAIL=" + $fail) -ForegroundColor $(if ($fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })
