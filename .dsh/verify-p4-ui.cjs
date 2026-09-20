/**
 * P4 UI verification: drives the exam-scheduling admin page and the student "my exams"
 * page in a real Chromium against the live dev server + backend.
 *
 * What only a browser can prove here:
 *   - the student page's 待考 / 已考 split, which is computed CLIENT-side from examTime
 *     (the server sends no phase field), so it can silently rot;
 *   - the computed end time shown next to "09:00 + 120 分钟";
 *   - that the HALF-OPEN interval rule is actually stated on screen, and that the page
 *     says it deliberately differs from P2's period rule. That rule is the one most likely
 *     to be "helpfully unified" by a future edit, and no API test would notice.
 *
 * Conflict detection itself is verified at the API layer (.dsh/verify-p4-api.ps1, 91
 * assertions incl. both boundary directions); this script checks the UI contract around it.
 *
 * Run from the repo root with dev server (5173), backend (8080) and Redis up:
 *   node .dsh/verify-p4-ui.cjs
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
const DIAG = path.join(__dirname, 'verify-p4-ui.diag.json')

const TERM = '2024-2025-1'
/** seeded: CS101/102/103 finals are in the future, CS104's makeup is in the past */
const SEEDED_TOTAL = 4
const SEEDED_PENDING = 3
const SEEDED_PAST = 1

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

async function uiLogin(page, username, password) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => sessionStorage.clear())
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.getByPlaceholder('请输入用户名').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL('**/index', { timeout: 20000 })
  await page.waitForSelector('.nav-menu .el-menu-item', { timeout: 15000 })
}

async function gotoPage(page, pathname, readySelector) {
  await page.goto(BASE + pathname, { waitUntil: 'domcontentloaded' })
  if (readySelector) await page.waitForSelector(readySelector, { timeout: 20000 })
  await page.waitForTimeout(700)
}

async function menuItems(page) {
  return (await page.locator('.nav-menu .el-menu-item').allTextContents()).map(norm)
}

/**
 * Row texts for ONE table.
 *
 * Deliberately scoped to .el-table__body-wrapper and with no "empty block" bail-out: when a
 * page holds two tables, an outer card also contains the other table's empty placeholder,
 * so an emptiness check on the card would report 0 rows for a table that has rows.
 */
async function rowTexts(scope) {
  return (await scope.locator('.el-table__body-wrapper tbody tr').allTextContents()).map(norm)
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: resolveChromium() })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage()

  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) =>
    pageErrors.push({
      phase: currentPhase,
      url: page.url(),
      message: e && e.message ? String(e.message) : null,
      stack: e && e.stack ? String(e.stack) : null,
      str: String(e),
    }),
  )
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(currentPhase + ' @ ' + page.url() + ' :: ' + m.text())
  })

  // ------------------------------------------------------------------
  phase('A. menus and route guards (teachers gain nothing)')
  await uiLogin(page, '10001', '123456')
  const teacherMenu = await menuItems(page)
  check('teacher menu has no 考试安排', !teacherMenu.includes('考试安排'), JSON.stringify(teacherMenu))
  check('teacher menu has no 我的考试', !teacherMenu.includes('我的考试'), JSON.stringify(teacherMenu))
  await page.goto(BASE + '/exam-manage', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('teacher is bounced off /exam-manage', !page.url().includes('exam-manage'), page.url())
  await page.goto(BASE + '/my-exams', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('teacher is bounced off /my-exams', !page.url().includes('my-exams'), page.url())

  await uiLogin(page, '2023001', '123456')
  const studentMenu = await menuItems(page)
  check('student menu offers 我的考试', studentMenu.includes('我的考试'), JSON.stringify(studentMenu))
  check('student menu hides the admin-only 考试安排', !studentMenu.includes('考试安排'), JSON.stringify(studentMenu))
  await page.goto(BASE + '/exam-manage', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('student is bounced off /exam-manage', !page.url().includes('exam-manage'), page.url())

  await uiLogin(page, 'admin01', '123456')
  const adminMenu = await menuItems(page)
  check('admin menu offers 考试安排', adminMenu.includes('考试安排'), JSON.stringify(adminMenu))
  check('admin menu hides the student-only 我的考试', !adminMenu.includes('我的考试'), JSON.stringify(adminMenu))

  // ------------------------------------------------------------------
  phase('B. admin /exam-manage renders the seeded exams')
  await gotoPage(page, '/exam-manage', '.el-table__body-wrapper tbody tr')
  const title = norm(await page.locator('.card-title').first().textContent())
  check('page title renders', title === '考试安排', title)

  const table = page.locator('.el-table').first()
  const rows = await rowTexts(table)
  check('table lists all four seeded exams', rows.length === SEEDED_TOTAL, 'rows=' + rows.length)

  const makeupRow = rows.find((r) => r.includes('CS104'))
  const finalRow = rows.find((r) => r.includes('CS101'))
  check('the makeup exam is labelled 补考 (typeLabel on the wire)', !!makeupRow && makeupRow.includes('补考'), makeupRow)
  check('a final is labelled 期末', !!finalRow && finalRow.includes('期末'), finalRow)
  check('rows carry room and seat range', !!finalRow && finalRow.includes('1-101') && finalRow.includes('A'), finalRow)
  // CS101 runs 09:00 + 120 min, so the UI must show the 11:00 end even though the API
  // sends no endTime field (it is a non-getter Java method and is NOT serialized).
  check('the computed end time is shown (09:00 + 120min -> 11:00)', !!finalRow && finalRow.includes('11:00'), finalRow)
  check('duration is shown in minutes', !!finalRow && /120/.test(finalRow), finalRow)

  const pageText = norm(await page.locator('.main-content, .el-main').first().innerText())
  check('the page explains the two conflict dimensions', pageText.includes('考场') && pageText.includes('学生'), pageText.slice(0, 240))

  // Filter by exam type through the UI
  const typeSelect = page.locator('.filter-bar .el-select, .exam-filter .el-select').first()
  if (await typeSelect.count()) {
    await typeSelect.click()
    await page.waitForTimeout(400)
    await page.locator('.el-select-dropdown__item').filter({ hasText: '补考' }).first().click()
    await page.getByRole('button', { name: '查询' }).click()
    await page.waitForTimeout(1500)
    const filtered = await rowTexts(table)
    check('filtering by 补考 narrows the table to one row', filtered.length === 1, 'rows=' + filtered.length)
    await page.getByRole('button', { name: '重置' }).click()
    await page.waitForTimeout(1500)
    check('reset restores all four rows', (await rowTexts(table)).length === SEEDED_TOTAL, 'rows=' + (await rowTexts(table)).length)
  } else {
    check('filter bar exposes an exam-type select', false, 'select not found')
  }

  // The create dialog: the half-open rule must be stated on screen.
  await page.getByRole('button', { name: '新建考试' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '新建考试' })
  await dialog.waitFor({ state: 'visible', timeout: 15000 })
  check('create dialog opens', (await dialog.count()) === 1)
  const dialogText = norm(await dialog.innerText())
  check('dialog states the half-open overlap rule', dialogText.includes('半开'), dialogText.slice(0, 300))
  check(
    'dialog works through the touching example (09:00-11:00 vs 11:00-13:00)',
    dialogText.includes('09:00-11:00') && dialogText.includes('11:00-13:00'),
    dialogText.slice(0, 400),
  )
  check(
    'dialog says this differs from the period rule (3-4 vs 4-5)',
    dialogText.includes('3-4') && dialogText.includes('4-5'),
    dialogText.slice(0, 400),
  )
  check('dialog exposes the expected form fields', ['课程', '考试类型', '开始时间', '考试时长', '考场', '座位号段', '监考教师'].every((l) => dialogText.includes(l)), dialogText.slice(0, 400))
  const cancelBtn = dialog.getByRole('button', { name: '取消' })
  if (await cancelBtn.count()) await cancelBtn.first().click()
  await page.waitForTimeout(800)
  check('dialog can be dismissed without creating anything', (await rowTexts(table)).length === SEEDED_TOTAL, 'rows=' + (await rowTexts(table)).length)

  // ------------------------------------------------------------------
  phase('C. student /my-exams: the 待考/已考 split is computed client-side')
  await uiLogin(page, '2023001', '123456')
  const stuToken = await page.evaluate(() => sessionStorage.getItem('token'))
  const apiExams = await (async () => {
    const r = await fetch(API + '/exam/my', { headers: { token: stuToken } })
    const j = await r.json()
    return j.data
  })()
  check('precondition: the API returns four exams', apiExams.length === SEEDED_TOTAL, 'n=' + apiExams.length)

  await gotoPage(page, '/my-exams', '.card-title')
  const titles = (await page.locator('.card-title').allTextContents()).map(norm)
  const pendingTitle = titles.find((t) => t.startsWith('待考'))
  const pastTitle = titles.find((t) => t.startsWith('已考'))
  check('page shows a 待考 section', !!pendingTitle, JSON.stringify(titles))
  check('page shows an 已考 section', !!pastTitle, JSON.stringify(titles))
  check('待考 count matches the server (3)', !!pendingTitle && pendingTitle.includes(String(SEEDED_PENDING)), pendingTitle)
  check('已考 count matches the server (1)', !!pastTitle && pastTitle.includes(String(SEEDED_PAST)), pastTitle)

  const summary = norm(await page.locator('.exam-summary').first().innerText())
  check('summary states the total and pending counts', summary.includes('共 ' + SEEDED_TOTAL + ' 场') && summary.includes('待考 ' + SEEDED_PENDING + ' 场'), summary)
  check('summary names the nearest upcoming exam', summary.includes('最近一场'), summary)
  check('summary spells out the nearest exam time', /\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(summary), summary)

  // Two tables on the page, in section order: 待考 first, then 已考.
  const tables = page.locator('.el-table')
  const pendingRows = await rowTexts(tables.nth(0))
  check('待考 lists three exams', pendingRows.length === SEEDED_PENDING, 'rows=' + pendingRows.length)
  check('待考 rows are the finals', pendingRows.length > 0 && pendingRows.every((r) => r.includes('期末')), pendingRows.join(' | '))
  check('待考 rows carry room and seat range', pendingRows.length > 0 && pendingRows.every((r) => r.includes('教') && r.includes('区')), pendingRows.join(' | '))
  const pastRows = (await tables.count()) > 1 ? await rowTexts(tables.nth(1)) : []
  check('已考 lists the past makeup exam', pastRows.length === SEEDED_PAST && pastRows[0].includes('CS104'), pastRows.join(' | '))

  // The page must be honest that it only covers selected courses
  const pageText2 = norm(await page.locator('.main-content, .el-main').first().innerText())
  check('page explains it only covers selected courses', pageText2.includes('已选课程'), pageText2.slice(0, 260))

  // "只看未开考" is an el-checkbox whose @change reloads immediately (no 查询 needed).
  const cb = page.locator('.el-checkbox').filter({ hasText: '只看未开考' }).first()
  check('page exposes the 只看未开考 filter', (await cb.count()) === 1, 'checkbox not found')
  if (await cb.count()) {
    await cb.click()
    await page.waitForTimeout(1800)
    const titles2 = (await page.locator('.card-title').allTextContents()).map(norm)
    const pending2 = titles2.find((t) => t.startsWith('待考'))
    const past2 = titles2.find((t) => t.startsWith('已考'))
    check('upcoming-only keeps the three pending exams', !!pending2 && pending2.includes(String(SEEDED_PENDING)), JSON.stringify(titles2))
    check('upcoming-only empties the 已考 section', !!past2 && past2.includes('0'), JSON.stringify(titles2))
    const summary2 = norm(await page.locator('.exam-summary').first().innerText())
    check('upcoming-only updates the summary total to 3', summary2.includes('共 ' + SEEDED_PENDING + ' 场'), summary2)
  }

  // ------------------------------------------------------------------
  phase('D. health')
  fs.writeFileSync(DIAG, JSON.stringify({ pageErrors, consoleErrors }, null, 2), 'utf8')
  console.log('\n(diagnostics written to .dsh/verify-p4-ui.diag.json)')
  check('no uncaught page errors during the whole run', pageErrors.length === 0, pageErrors.length + ' error(s), see .dsh/verify-p4-ui.diag.json')

  const after = await (async () => {
    const r = await fetch(API + '/exam/my', { headers: { token: stuToken } })
    return (await r.json()).data
  })()
  check('seeded exams are untouched by the run', after.length === SEEDED_TOTAL, 'n=' + after.length)

  await browser.close()

  console.log('\n========================================')
  console.log('RESULT: PASS=' + pass + '  FAIL=' + fail)
  console.log('========================================')
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('FATAL: ' + (e && e.stack ? e.stack : e))
  try {
    fs.writeFileSync(DIAG, JSON.stringify({ fatal: String(e && e.stack ? e.stack : e) }, null, 2), 'utf8')
  } catch {
    /* ignore */
  }
  process.exit(1)
})
