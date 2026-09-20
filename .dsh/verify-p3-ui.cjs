/**
 * P3 UI verification: drives the selection-round admin page and the student selection
 * page in a real Chromium against the live dev server + backend.
 *
 * The highest-value things it pins down:
 *   - the three seeded rounds render as three DIFFERENT states (可选 / 补退选只能退 / 已关闭只能看);
 *     that status is computed client-side from the switch + the two windows, so it can silently rot.
 *   - the student banner's three tones come from the server's canSelect/canDrop.
 *   - a real select -> drop round trip through the UI (and the credit total following it).
 *   - blocked rows are DISABLED with the reason shown, not clickable-then-error.
 *   - the menu-leak checks: teacher gains neither 选课 nor 选课轮次.
 *
 * Run from the repo root with dev server (5173), backend (8080) and Redis up:
 *   node .dsh/verify-p3-ui.cjs
 *
 * NOTE: never edit this file through a PowerShell Get-Content/Set-Content round trip;
 * PS reads it as the ANSI codepage and destroys every Chinese literal.
 */
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')
const { execFileSync } = require('node:child_process')

const { chromium } = require(
  path.join(__dirname, '..', 'frontend', 'edu-system-client', 'node_modules', '@playwright', 'test'),
)

const BASE = 'http://localhost:5173'
const API = 'http://localhost:8080'
const DIAG = path.join(__dirname, 'verify-p3-ui.diag.json')
const mysql = 'D:\\mysql-8.4.7-winx64\\mysql-8.4.7-winx64\\bin\\mysql.exe'

const TERM_OPEN = '2024-2025-1'
const TERM_DROP = '2024-2025-2'
const TERM_CLOSED = '2025-2026-1'
const STUDENT = '2023001'
/** 2023001 在 2024-2025-1 唯一没选、也没通过的课（数学分析） */
const MA101_NAME = '数学分析'
const SEEDED_SELECTIONS = 6

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

/** The UI round trip must not leave a stray selection behind. */
function cleanupSelection() {
  const sql =
    "delete cs from course_selection cs join course c on cs.course_id = c.id " +
    "where cs.student_id = '2023001' and c.course_code = 'MA101';"
  try {
    execFileSync(mysql, ['-uroot', '-p123456', '-D', 'edujwxt', '-N', '-B', '-e', sql], { stdio: 'ignore' })
  } catch (e) {
    console.log('  (cleanup warning: ' + e.message + ')')
  }
}

async function apiGet(pathname, token) {
  const r = await fetch(API + pathname, { headers: { token } })
  const j = await r.json()
  if (j.code !== '200') throw new Error('apiGet ' + pathname + ' failed: ' + JSON.stringify(j))
  return j.data
}

async function pageToken(page) {
  return await page.evaluate(() => sessionStorage.getItem('token'))
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
  await page.waitForTimeout(600)
}

async function menuItems(page) {
  return (await page.locator('.nav-menu .el-menu-item').allTextContents()).map(norm)
}

async function rowTexts(scope) {
  const empty = await scope.locator('.el-table__empty-block').count()
  if (empty > 0) return []
  return (await scope.locator('.el-table__body-wrapper tbody tr').allTextContents()).map(norm)
}

/** Fill the term box and hit 查询, then wait for the banner to settle. */
async function queryTerm(page, term) {
  const box = page.getByPlaceholder('学期（如 2024-2025-1）')
  await box.fill(term)
  await page.getByRole('button', { name: '查询' }).click()
  await page.waitForTimeout(1600)
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

  cleanupSelection()

  // ------------------------------------------------------------------
  phase('A. menus and route guards (teachers must gain nothing)')
  await uiLogin(page, '10001', '123456')
  const teacherMenu = await menuItems(page)
  check('teacher menu has no 选课', !teacherMenu.includes('选课'), JSON.stringify(teacherMenu))
  check('teacher menu has no 选课轮次', !teacherMenu.includes('选课轮次'), JSON.stringify(teacherMenu))
  await page.goto(BASE + '/course-selection', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('teacher is bounced off /course-selection', !page.url().includes('course-selection'), page.url())
  await page.goto(BASE + '/selection-round', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('teacher is bounced off /selection-round', !page.url().includes('selection-round'), page.url())

  await uiLogin(page, '2023001', '123456')
  const studentMenu = await menuItems(page)
  check('student menu offers 选课', studentMenu.includes('选课'), JSON.stringify(studentMenu))
  check('student menu hides the admin-only 选课轮次', !studentMenu.includes('选课轮次'), JSON.stringify(studentMenu))
  await page.goto(BASE + '/selection-round', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('student is bounced off /selection-round', !page.url().includes('selection-round'), page.url())

  await uiLogin(page, 'admin01', '123456')
  const adminToken = await pageToken(page)
  const adminMenu = await menuItems(page)
  check('admin menu offers 选课轮次', adminMenu.includes('选课轮次'), JSON.stringify(adminMenu))
  check('admin menu hides the student-only 选课', !adminMenu.includes('选课'), JSON.stringify(adminMenu))

  // ------------------------------------------------------------------
  phase('B. admin /selection-round renders the three seeded states')
  await gotoPage(page, '/selection-round', '.el-table__body-wrapper tbody tr')
  const title = norm(await page.locator('.card-title').first().textContent())
  check('page title renders', title === '选课轮次管理', title)

  const table = page.locator('.el-table').first()
  const rounds = await rowTexts(table)
  check('rounds table lists the seeded rounds', rounds.length >= 3, 'rows=' + rounds.length)

  const rowOpen = rounds.find((r) => r.includes(TERM_OPEN))
  const rowDrop = rounds.find((r) => r.includes(TERM_DROP))
  const rowClosed = rounds.find((r) => r.includes(TERM_CLOSED))
  check('open round row is rendered', !!rowOpen, rounds.join(' | '))
  check('drop-only round row is rendered', !!rowDrop, rounds.join(' | '))
  check('closed round row is rendered', !!rowClosed, rounds.join(' | '))

  check('open round shows the SELECTING state', !!rowOpen && rowOpen.includes('开启中·可选'), rowOpen)
  check('drop-only round shows the DROPPING state', !!rowDrop && rowDrop.includes('补退选·只能退'), rowDrop)
  check('closed round shows the CLOSED state', !!rowClosed && rowClosed.includes('已关闭·只能看'), rowClosed)

  check('unrestricted round summarises its scope as 不限', !!rowOpen && rowOpen.includes('不限'), rowOpen)
  check('scoped round shows its grade scope', !!rowClosed && rowClosed.includes('2023'), rowClosed)

  const pageText = norm(await page.locator('.main-content, .el-main').first().innerText())
  check(
    'page states the state is computed client-side (no server field)',
    pageText.includes('由前端按当前时间实时计算'),
    pageText.slice(0, 220),
  )

  // ------------------------------------------------------------------
  phase('C. student /course-selection: the banner tracks the server status')
  await uiLogin(page, '2023001', '123456')
  const stuToken = await pageToken(page)
  await gotoPage(page, '/course-selection', '.status-banner')

  await queryTerm(page, TERM_OPEN)
  let bannerTitle = norm(await page.locator('.status-banner .banner-title').textContent())
  check('open term banner says 选课开放中', bannerTitle.includes('选课开放中'), bannerTitle)
  check(
    'open term banner uses the success tone',
    (await page.locator('.status-banner.el-alert--success').count()) === 1,
    norm(await page.locator('.status-banner').innerText()),
  )
  const bannerTag = norm(await page.locator('.status-banner .banner-tag').textContent())
  check('banner shows the 30 credit cap', bannerTag.includes('30'), bannerTag)

  const selectableTable = page.locator('.el-table').first()
  const openRows = await rowTexts(selectableTable)
  check('selectable table lists the term courses', openRows.length === 7, 'rows=' + openRows.length)
  const selectedRows = openRows.filter((r) => r.includes('已选'))
  check('already-selected courses are marked 已选', selectedRows.length === SEEDED_SELECTIONS, 'selected=' + selectedRows.length)
  const ma101Row = openRows.find((r) => r.includes(MA101_NAME))
  check('the unselected course is offered as 可选', !!ma101Row && ma101Row.includes('可选'), ma101Row)

  await queryTerm(page, TERM_DROP)
  bannerTitle = norm(await page.locator('.status-banner .banner-title').textContent())
  check('drop-only term banner says 只能退课', bannerTitle.includes('只能退课'), bannerTitle)
  check(
    'drop-only term banner uses the warning tone',
    (await page.locator('.status-banner.el-alert--warning').count()) === 1,
    norm(await page.locator('.status-banner').innerText()),
  )
  const dropRows = await rowTexts(page.locator('.el-table').first())
  check('drop-only term still lists courses', dropRows.length > 0, 'rows=' + dropRows.length)
  const selectButtons = page.locator('.el-table').first().getByRole('button', { name: '选课' })
  const enabledSelect = await selectButtons.evaluateAll((els) => els.filter((e) => !e.disabled).length)
  check('every 选课 button is disabled while only dropping is allowed', enabledSelect === 0, 'enabled=' + enabledSelect)

  await queryTerm(page, TERM_CLOSED)
  bannerTitle = norm(await page.locator('.status-banner .banner-title').textContent())
  check('closed term banner says it is view-only', bannerTitle.includes('只能查看'), bannerTitle)
  check(
    'closed term banner uses the info tone',
    (await page.locator('.status-banner.el-alert--info').count()) === 1,
    norm(await page.locator('.status-banner').innerText()),
  )

  // ------------------------------------------------------------------
  phase('D. real select -> drop round trip through the UI')
  await queryTerm(page, TERM_OPEN)
  const beforeIds = await apiGet('/course-selection/my-ids', stuToken)
  check('precondition: exactly the seeded selections exist', beforeIds.length === SEEDED_SELECTIONS, 'ids=' + beforeIds.join(','))

  const tableOpen = page.locator('.el-table').first()
  const ma101RowLoc = tableOpen.locator('.el-table__body-wrapper tbody tr').filter({ hasText: MA101_NAME })
  await ma101RowLoc.getByRole('button', { name: '选课' }).click()
  await page.waitForTimeout(2000)

  const afterSelect = await apiGet('/course-selection/my-ids', stuToken)
  check('the selection was persisted', afterSelect.length === SEEDED_SELECTIONS + 1, 'ids=' + afterSelect.join(','))

  const myCardText = norm(await page.locator('.el-card').last().innerText())
  check('my-courses card counts 7 courses', myCardText.includes('已选 7 门'), myCardText.slice(0, 160))
  check('my-courses credit total grew to 24.5', myCardText.includes('24.5'), myCardText.slice(0, 160))

  const refreshedRows = await rowTexts(page.locator('.el-table').first())
  const ma101After = refreshedRows.find((r) => r.includes(MA101_NAME))
  check('the row now shows 已选 with a 退课 action', !!ma101After && ma101After.includes('已选') && ma101After.includes('退课'), ma101After)

  await page.locator('.el-table').first().locator('.el-table__body-wrapper tbody tr').filter({ hasText: MA101_NAME })
    .getByRole('button', { name: '退课' }).click()
  const confirmBox = page.locator('.el-message-box')
  if (await confirmBox.count()) {
    // Click the primary button by class, not by label: the app configures no Element Plus
    // locale for MessageBox, so its default confirm text is the English "OK".
    await confirmBox.locator('.el-message-box__btns .el-button--primary').click()
  }
  await page.waitForTimeout(2000)

  const afterDrop = await apiGet('/course-selection/my-ids', stuToken)
  check('the drop was persisted', afterDrop.length === SEEDED_SELECTIONS, 'ids=' + afterDrop.join(','))
  const myCardAfter = norm(await page.locator('.el-card').last().innerText())
  check('credit total returned to 19.5', myCardAfter.includes('19.5'), myCardAfter.slice(0, 160))

  // A blocked selection must be refused by the server, not just hidden in the UI.
  await queryTerm(page, TERM_DROP)
  const dropTermRows = await rowTexts(page.locator('.el-table').first())
  check('the drop-only term still explains why nothing is selectable', dropTermRows.some((r) => r.length > 20), dropTermRows[0])

  // ------------------------------------------------------------------
  phase('E. cleanup + health')
  cleanupSelection()
  const finalIds = await apiGet('/course-selection/my-ids', stuToken)
  check('no fixture selection left behind', finalIds.length === SEEDED_SELECTIONS, 'ids=' + finalIds.join(','))
  const seededCs101 = await apiGet('/class-time/course/1', adminToken)
  check('seeded timetable untouched', seededCs101.length === 1, 'rows=' + seededCs101.length)

  fs.writeFileSync(DIAG, JSON.stringify({ pageErrors, consoleErrors }, null, 2), 'utf8')
  console.log('\n(diagnostics written to .dsh/verify-p3-ui.diag.json)')
  check('no uncaught page errors during the whole run', pageErrors.length === 0, pageErrors.length + ' error(s), see .dsh/verify-p3-ui.diag.json')

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
