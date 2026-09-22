/**
 * Academic-warning UI verification: does the popup actually appear, once, and go away?
 *
 * WHAT ONLY A BROWSER CAN PROVE HERE
 *   The backend rule (credits >= threshold) and the watermark idempotency are already asserted at
 *   the API layer (.dsh/verify-warning-api.ps1 style) and in AcademicWarningServiceTest. What
 *   neither can show is the actual user-facing promise of JW-01 5.3: the student LOGS IN and a
 *   notice appears -- and, on the next login, does NOT appear again. That lives in
 *   composables/useAcademicWarning.ts + Layout.vue (trigger timing, the ElMessageBox contract,
 *   the "remember by token" guard) and can rot silently with every refactor.
 *
 * RUNBOOK (the popup is stateful by design, so the fixture must be reset first)
 *   .\.dsh\reset-academic-warning.ps1        # 1. clean fixture
 *   node .dsh\verify-warning-ui.cjs          # 2. this script (seeds + asserts + cleans its grades)
 *   .\.dsh\reset-academic-warning.ps1        # 3. leave the DB as you found it
 *
 * Requires: Redis + backend (8080) + Vite dev server (5173). Chromium launching needs a one-shot
 * sandbox escalation (--remote-debugging-pipe is a named pipe).
 *
 * NOTE: never edit this file through a PowerShell Get-Content/Set-Content round trip;
 * PS reads it as the ANSI codepage and destroys every Chinese literal.
 */
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')

const { chromium } = require(
  path.join(__dirname, '..', 'frontend', 'edu-system-client', 'node_modules', '@playwright', 'test'),
)

const BASE = 'http://localhost:5173'
const API = 'http://localhost:8080'
const DIAG = path.join(__dirname, 'verify-warning-ui.diag.json')

/** demo fixture (see reset-academic-warning.ps1): student with no grades on these two courses */
const STUDENT = { user: '2024002', pass: '123456' }
const TEACHER = { user: '10001', pass: '123456' }
const SEED_COURSES = [
  { courseId: 1, courseCode: 'CS101', name: 'Java程序设计', credit: 4.0 },
  { courseId: 10, courseCode: 'CS107', name: '操作系统', credit: 4.0 },
]
/** 4.0 + 4.0 credits failed, threshold is 8 -> exactly at the trigger line */
const EXPECTED_CREDITS = 8

let pass = 0
let fail = 0

function check(name, cond, detail) {
  if (cond) {
    pass++
    console.log('  [PASS] ' + name)
  } else {
    fail++
    console.log('  [FAIL] ' + name + (detail ? '  -> ' + detail : ''))
  }
}

const norm = (s) => (s || '').replace(/\s+/g, ' ').trim()

let currentPhase = 'start'
function phase(title) {
  currentPhase = title
  console.log('\n=== ' + title + ' ===')
}

function resolveChromium() {
  const root = path.join(os.homedir(), 'AppData', 'Local', 'ms-playwright')
  if (!fs.existsSync(root)) return undefined
  const builds = fs
    .readdirSync(root)
    .filter((d) => /^chromium-\d+$/.test(d))
    .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]))
  for (const build of builds) {
    for (const dir of ['chrome-win64', 'chrome-win']) {
      const exe = path.join(root, build, dir, 'chrome.exe')
      if (fs.existsSync(exe)) return exe
    }
  }
  return undefined
}

// ---------------------------------------------------------------- API helpers

async function login(username, password) {
  const r = await fetch(API + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const j = await r.json()
  return j.token
}

async function api(method, url, token, body) {
  const headers = { token }
  if (body) headers['Content-Type'] = 'application/json; charset=utf-8'
  const r = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined })
  const text = await r.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* non-JSON error page */
  }
  return { status: r.status, json, text }
}

/** The warning status of the demo student, taken from the API (authoritative for the UI). */
const warningOf = (token) => api('GET', API + '/academic-warning/my', token)

/** POST /score as the owning teacher: total = usual*0.4 + exam*0.6, computed server-side */
function failingScore(courseId) {
  return { courseId, studentId: STUDENT.user, usualScore: 40, examScore: 45 } // 42.5 -> fail
}

/** Remove the grades this script seeded, so the DB is left as found */
async function cleanSeededGrades(teacherToken) {
  // NOTE: the list endpoint requires a teacher to pass `courseId` and checks the course is theirs
  // (an anti-IDOR guard in ScoreController). Querying by studentId alone answers
  // 403 "no permission to query this course's scores" -- correct behaviour, and a bug in the
  // first version of this cleanup.
  const ids = []
  for (const c of SEED_COURSES) {
    const list = await api('GET', API + '/score?page=1&pageSize=100&courseId=' + c.courseId, teacherToken)
    const rows = (list.json && list.json.data && (list.json.data.list || list.json.data.records)) || []
    for (const r of rows) {
      if (String(r.studentId) === STUDENT.user) ids.push(r.id)
    }
  }
  const failed = []
  for (const id of ids) {
    // Assert the response instead of firing and forgetting: the first version ignored it, so a
    // failing DELETE looked like "cleanup ran" while the rows were still there.
    const r = await api('DELETE', API + '/score/' + id, teacherToken)
    if (!r.json || r.json.code !== '200') failed.push({ id, code: r.json && r.json.code, text: r.text.slice(0, 120) })
  }
  if (failed.length) throw new Error('cleanup failed for: ' + JSON.stringify(failed))
  return ids
}

// ---------------------------------------------------------------- browser helpers

async function uiLogin(page, user, pass) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => sessionStorage.clear())
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.getByPlaceholder('请输入用户名').fill(user)
  await page.getByPlaceholder('请输入密码').fill(pass)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL('**/index', { timeout: 20000 })
  await page.waitForSelector('.nav-menu .el-menu-item', { timeout: 15000 })
}

/** Wait for the warning dialog and return its text; returns null when it never shows */
async function waitForWarningDialog(page, timeout = 8000) {
  try {
    await page.waitForSelector('.el-message-box', { state: 'visible', timeout })
    await page.waitForTimeout(200)
    return norm(await page.locator('.el-message-box').innerText())
  } catch {
    return null
  }
}

async function dismissWarningDialog(page) {
  await page.locator('.el-message-box__btns button').filter({ hasText: '我知道了' }).first().click()
  await page.waitForSelector('.el-message-box', { state: 'hidden', timeout: 8000 })
}

async function main() {
  // Tokens are fetched FRESH before each API step on purpose: logging in again -- which the
  // browser phases below do -- INVALIDATES the previous token for that account (the backend keeps
  // only the latest token per user in Redis; see LoginInterceptorTest.staleTokenIsRejected).
  // Reusing a token cached before a browser login is a guaranteed 401, which is exactly what the
  // first version of this script did: seeding "failed" with 登录已失效 and every later read null.
  const asTeacher = () => login(TEACHER.user, TEACHER.pass)
  const asStudent = () => login(STUDENT.user, STUDENT.pass)
  check('logins work (teacher + demo student)', !!(await asTeacher()) && !!(await asStudent()))

  // ------------------------------------------------------------------
  phase('A. fixture: this student has no failing grades yet (popup must stay silent)')
  const before = await warningOf(await asStudent())
  check('before seeding: business code 200', before.json && before.json.code === '200', before.text.slice(0, 160))
  const beforeData = before.json && before.json.data
  check('before seeding: warned=false', beforeData && beforeData.warned === false, JSON.stringify(beforeData))
  check('before seeding: shouldNotify=false', beforeData && beforeData.shouldNotify === false, JSON.stringify(beforeData))

  const browser = await chromium.launch({ headless: true, executablePath: resolveChromium() })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage()

  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) => pageErrors.push({ phase: currentPhase, str: String(e) }))
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(currentPhase + ' @ ' + page.url() + ' :: ' + m.text())
  })

  // ------------------------------------------------------------------
  phase('B. no warning -> no popup for this student')
  await uiLogin(page, STUDENT.user, STUDENT.pass)
  const quietDialog = await waitForWarningDialog(page, 4000)
  check('no dialog when nothing is triggered', quietDialog === null, JSON.stringify(quietDialog))

  // ------------------------------------------------------------------
  phase('C. a teacher never sees the popup (no warning feature)')
  await uiLogin(page, TEACHER.user, TEACHER.pass)
  const teacherDialog = await waitForWarningDialog(page, 4000)
  check('teacher gets no warning dialog', teacherDialog === null, JSON.stringify(teacherDialog))

  // ------------------------------------------------------------------
  phase('D. seed two failing grades (8.0 credits = exactly the threshold)')
  const teacherToken = await asTeacher()
  for (const c of SEED_COURSES) {
    const r = await api('POST', API + '/score', teacherToken, failingScore(c.courseId))
    check('seeded failing grade for ' + c.courseCode, r.json && r.json.code === '200', r.text.slice(0, 160))
  }
  const afterSeed = await warningOf(await asStudent())
  const seeded = afterSeed.json && afterSeed.json.data
  check('API: warned=true after seeding', seeded && seeded.warned === true, JSON.stringify(seeded))
  check('API: shouldNotify=true (never acknowledged)', seeded && seeded.shouldNotify === true, JSON.stringify(seeded))
  check(
    'API: failed credits == ' + EXPECTED_CREDITS,
    seeded && Number(seeded.failedCredits) === EXPECTED_CREDITS,
    seeded && String(seeded.failedCredits),
  )
  check('API: both courses listed', seeded && seeded.courses && seeded.courses.length === 2, JSON.stringify(seeded && seeded.courses))

  // ------------------------------------------------------------------
  phase('E. the student logs in -> the popup MUST appear, with explainable content')
  await uiLogin(page, STUDENT.user, STUDENT.pass)
  const dialogText = await waitForWarningDialog(page)
  check('warning dialog appears on login', dialogText !== null, 'no .el-message-box within timeout')
  if (dialogText) {
    check('dialog states how many courses failed', /2\s*门课程未通过/.test(dialogText), dialogText)
    check(
      'dialog states the accumulated credits and the threshold',
      /累计\s*8\s*学分/.test(dialogText) && /阈值\s*8\s*学分/.test(dialogText),
      dialogText,
    )
    check(
      'dialog lists both failed courses',
      dialogText.includes('Java程序设计') && dialogText.includes('操作系统'),
      dialogText,
    )
    check('dialog says it is a notice only (no automatic action)', /不会自动产生任何处理/.test(dialogText), dialogText)
    check('dialog offers a single acknowledge button', /我知道了/.test(dialogText), dialogText)
    await dismissWarningDialog(page)
    check('dialog closes after acknowledging', (await page.locator('.el-message-box').count()) === 0, 'still visible')
  }

  const afterRead = await warningOf(await asStudent())
  const readData = afterRead.json && afterRead.json.data
  check('API: after acknowledging, shouldNotify=false', readData && readData.shouldNotify === false, afterRead.text.slice(0, 160))
  check('API: warned is still true (the fact is not hidden)', readData && readData.warned === true, JSON.stringify(readData))

  // ------------------------------------------------------------------
  phase('F. next login: NOT again (only once, until it gets worse)')
  await uiLogin(page, STUDENT.user, STUDENT.pass)
  const secondDialog = await waitForWarningDialog(page, 5000)
  check('no dialog on the next login', secondDialog === null, JSON.stringify(secondDialog))

  // ------------------------------------------------------------------
  phase('G. hygiene: no unhandled page errors along the way')
  check('no uncaught page errors', pageErrors.length === 0, JSON.stringify(pageErrors).slice(0, 400))
  check('no console errors', consoleErrors.length === 0, JSON.stringify(consoleErrors).slice(0, 400))

  // ------------------------------------------------------------------
  phase('H. cleanup: this script removes the grades it seeded')
  const removed = await cleanSeededGrades(await asTeacher())
  check('seeded grades removed (' + removed.length + ')', removed.length === SEED_COURSES.length, JSON.stringify(removed))
  const afterClean = await warningOf(await asStudent())
  const cleanData = afterClean.json && afterClean.json.data
  check('API: back to warned=false after cleanup', cleanData && cleanData.warned === false, afterClean.text.slice(0, 160))
  check(
    'note: the read-watermark row is left behind on purpose (run reset-academic-warning.ps1)',
    true,
  )

  await browser.close()

  if (fail > 0) {
    fs.writeFileSync(
      DIAG,
      JSON.stringify({ pageErrors, consoleErrors, dialogText }, null, 2),
      'utf8',
    )
    console.log('\n(diagnostics written to ' + DIAG + ')')
  }

  console.log('\n========================================')
  console.log('RESULT: PASS=' + pass + '  FAIL=' + fail)
  console.log('========================================')
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('FATAL: ' + (e && e.stack ? e.stack : e))
  process.exit(1)
})
