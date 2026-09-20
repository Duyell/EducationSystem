/**
 * P1 UI verification: drives the three new pages in a real Chromium against the
 * live dev server + backend, and checks the rendered DOM against the API payload.
 *
 * Why this exists: verify-p1-api.ps1 proves the *contract*, but a page can still
 * render nothing while every API call returns 200. This script is the only check
 * that the data actually reaches the screen -- it already caught two real defects
 * (CreditProgressCard read `audit.satisfied`, a field Jackson never serializes, so
 * the "all requirements met" branch was unreachable; and Layout offered teachers
 * the student-only plan/GPA menu entries).
 *
 * Run from the repo root with the dev server (5173), backend (8080) and Redis up:
 *   node .dsh/verify-p1-ui.cjs
 *
 * Diagnostics (browser errors + console errors) are written to
 * .dsh/verify-p1-ui.diag.json -- read that file instead of the console when a
 * browser-side error needs investigating, because the Windows console mangles
 * non-ASCII and bracketed text on the way through PowerShell.
 *
 * NOTE: never edit this file through a PowerShell Get-Content/Set-Content round
 * trip; PS reads it as the ANSI codepage and destroys every Chinese literal.
 */
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')

// @playwright/test lives in the frontend package, but this script sits in .dsh/,
// so resolve it explicitly instead of relying on node's upward node_modules walk.
const { chromium } = require(
  path.join(__dirname, '..', 'frontend', 'edu-system-client', 'node_modules', '@playwright', 'test'),
)

const BASE = 'http://localhost:5173'
const API = 'http://localhost:8080'
const DIAG = path.join(__dirname, 'verify-p1-ui.diag.json')

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

/** Phase markers make a browser error attributable to a section of this script. */
let currentPhase = 'start'
function phase(title) {
  currentPhase = title
  console.log('\n=== ' + title + ' ===')
}

/**
 * The locally installed Chromium revision does not have to match the one this
 * playwright-core pins (here: installed 1223, pinned 1217), and Playwright refuses
 * to start when the pinned one is missing. Point it at the newest installed full
 * build instead of downloading anything.
 */
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

/**
 * The token the browser is actually using.
 *
 * Do NOT reuse a token from a separate API login: logging in a second time rotates
 * `token:<username>` in Redis, and the interceptor rejects any token that no longer
 * matches -- the browser session would start getting 401s.
 */
async function pageToken(page) {
  return await page.evaluate(() => sessionStorage.getItem('token'))
}

async function apiGet(pathname, token) {
  const r = await fetch(API + pathname, { headers: { token } })
  const j = await r.json()
  if (j.code !== '200') {
    throw new Error('apiGet ' + pathname + ' failed: HTTP ' + r.status + ' ' + JSON.stringify(j))
  }
  return j.data
}

/** Real UI login, so the sessionStorage the router guard reads is set the same way a user sets it. */
async function uiLogin(page, username, password) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  // Drop any previous user's session, otherwise a stale role lingers in the menu/Layout.
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
  await page.waitForSelector(readySelector, { timeout: 20000 })
  await page.waitForTimeout(400) // let the XHR settle into the DOM
}

/**
 * Park the pointer somewhere inert and dismiss any lingering popper.
 *
 * The header's user menu is an el-dropdown with the default hover trigger. Closing
 * the right-hand drawer leaves the pointer exactly where that dropdown lives, so it
 * opens and its popper then intercepts clicks meant for the card header underneath.
 */
async function parkMouse(page) {
  await page.mouse.move(4, 640)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
}

async function menuItems(page) {
  return (await page.locator('.nav-menu .el-menu-item').allTextContents()).map(norm)
}

async function tableRowCount(scope) {
  const empty = await scope.locator('.el-table__empty-block').count()
  if (empty > 0) return 0
  return await scope.locator('.el-table__body-wrapper tbody tr').count()
}

async function rowTexts(scope) {
  return (await scope.locator('.el-table__body-wrapper tbody tr').allTextContents()).map(norm)
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: resolveChromium() })
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()

  // Surface browser-side problems instead of letting them fail a later assertion mysteriously.
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) =>
    pageErrors.push({
      phase: currentPhase,
      url: page.url(),
      kind: Object.prototype.toString.call(e),
      message: e && e.message ? String(e.message) : null,
      stack: e && e.stack ? String(e.stack) : null,
      json: (() => {
        try {
          return JSON.stringify(e)
        } catch {
          return 'unserialisable'
        }
      })(),
      str: String(e),
    }),
  )
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(currentPhase + ' @ ' + page.url() + ' :: ' + m.text())
  })

  // ------------------------------------------------------------------
  // A. student 2023001 -- /gpa
  // ------------------------------------------------------------------
  phase('A. student 2023001 -> /gpa (renders API values)')
  await uiLogin(page, '2023001', '123456')
  const s1Token = await pageToken(page)
  const gpaApi = await apiGet('/gpa/my', s1Token)
  const auditApi = await apiGet('/gpa/audit/my', s1Token)

  await gotoPage(page, '/gpa', '.main-value')

  const gpaText = norm(await page.locator('.main-value').textContent())
  check('GPA headline equals the API value', gpaText === String(gpaApi.gpa.gpa), 'dom=' + gpaText + ' api=' + gpaApi.gpa.gpa)

  const gpaHint = norm(await page.locator('.main-hint').textContent())
  const expectedEquiv = ((Number(gpaApi.gpa.gpa) + 5) * 10).toFixed(2)
  check('equivalent 100-point score is derived from the GPA', gpaHint.includes(expectedEquiv), 'dom=' + gpaHint)

  // Read .stat-value / .stat-label per item: the rank value itself contains a space
  // ("1 / 2"), so splitting the item's combined text would mis-parse it.
  const statItems = page.locator('.summary-stats .stat-item')
  const statByLabel = {}
  for (let i = 0; i < (await statItems.count()); i++) {
    const item = statItems.nth(i)
    const label = norm(await item.locator('.stat-label').textContent())
    const value = norm(await item.locator('.stat-value').textContent())
    statByLabel[label] = value
  }
  const statsText = norm(await page.locator('.summary-stats').innerText())
  check('计入学分 shows the API totalCredit', statByLabel['计入学分'] === String(gpaApi.gpa.totalCredit), JSON.stringify(statByLabel))
  check('已通过课程 shows the API passedCount', statByLabel['已通过课程'] === String(gpaApi.gpa.passedCount), JSON.stringify(statByLabel))
  check(
    '专业内排名 shows rank / total',
    statByLabel['专业内排名'] === gpaApi.rank.rank + ' / ' + gpaApi.rank.total,
    JSON.stringify(statByLabel),
  )
  check('rank block names the major and grade', statsText.includes(gpaApi.rank.majorName + ' ' + gpaApi.rank.grade + ' 级'), statsText)

  const courseRows = await rowTexts(page.locator('.course-table'))
  check('course GPA table lists every API detail row', courseRows.length === gpaApi.gpa.details.length, 'rows=' + courseRows.length + ' api=' + gpaApi.gpa.details.length)
  const first = gpaApi.gpa.details.slice().sort((a, b) => b.gradePoint - a.gradePoint)[0]
  check('table is sorted by grade point desc', courseRows[0].includes(first.courseCode), 'row0=' + courseRows[0] + ' expected=' + first.courseCode)
  const cs103Row = courseRows.find((r) => r.includes('CS103'))
  check('a fractional score round-trips (90.4 -> 4.04)', !!cs103Row && cs103Row.includes('90.4') && cs103Row.includes('4.04'), 'row=' + cs103Row)
  check('no makeup tag when nothing was made up', !courseRows.join(' ').includes('补考记 60'), courseRows.join(' | '))

  const progressRows = (await page.locator('.credit-progress .progress-row').allTextContents()).map(norm)
  check('credit progress renders 3 rows', progressRows.length === 3, 'rows=' + progressRows.length)
  const totalRow = progressRows.find((r) => r.startsWith('总学分'))
  const requiredRow = progressRows.find((r) => r.startsWith('必修学分'))
  check('总学分 row shows earned/required and 未达标', !!totalRow && totalRow.includes(auditApi.earnedCredits + ' / ' + auditApi.totalCredits) && totalRow.includes('未达标'), totalRow)
  check('必修学分 row shows earned/required and 已达标', !!requiredRow && requiredRow.includes(auditApi.earnedRequiredCredit + ' / ' + auditApi.requiredCredits) && requiredRow.includes('已达标'), requiredRow)

  const creditAlert = norm(await page.locator('.credit-alert .el-alert__title').textContent())
  check('alert is the warning branch while electives are unmet', creditAlert.includes('尚未满足毕业学分要求'), creditAlert)
  check('empty missing-required list renders 必修课已全部通过', (await page.locator('.missing-none').count()) === 1)

  // ------------------------------------------------------------------
  // B. the "all requirements met" branch (unreachable with seed data)
  // ------------------------------------------------------------------
  phase('B. /training-plan with a fully satisfied audit (mocked response)')
  await page.route('**/api/training-plan/my', async (route) => {
    const resp = await route.fetch()
    const body = await resp.json()
    body.data.audit.creditSatisfied = true
    body.data.audit.requiredSatisfied = true
    body.data.audit.electiveSatisfied = true
    await route.fulfill({ response: resp, body: JSON.stringify(body) })
  })
  await gotoPage(page, '/training-plan', '.plan-info')
  const satisfiedAlert = norm(await page.locator('.credit-alert .el-alert__title').textContent())
  check('all three flags true -> success branch is reachable', satisfiedAlert.includes('毕业学分要求已全部达成'), satisfiedAlert)
  check('success branch uses the success alert style', (await page.locator('.credit-alert.el-alert--success').count()) === 1)
  await page.unroute('**/api/training-plan/my')

  // ------------------------------------------------------------------
  // C. student 2023001 -- /training-plan (real data)
  // ------------------------------------------------------------------
  phase('C. student 2023001 -> /training-plan (plan + courses + audit)')
  const planApi = await apiGet('/training-plan/my', s1Token)
  await gotoPage(page, '/training-plan', '.plan-info')

  const infoText = norm(await page.locator('.plan-info').innerText())
  check('plan name is rendered', infoText.includes(planApi.plan.planName), infoText)
  check('total credit requirement is rendered', infoText.includes(String(planApi.plan.totalCredits)), infoText)
  check('required credit requirement is rendered', infoText.includes(String(planApi.plan.requiredCredits)), infoText)
  check('grade is rendered with 级 suffix', infoText.includes(planApi.plan.grade + ' 级'), infoText)
  check('enabled plan shows 启用', infoText.includes('启用'), infoText)

  const sectionTitles = (await page.locator('.plan-courses .section-title').allTextContents()).map(norm)
  const requiredApi = planApi.courses.filter((c) => c.category === 'REQUIRED')
  const electiveApi = planApi.courses.filter((c) => c.category === 'ELECTIVE')
  check('必修 section counts match the API', sectionTitles[0] === '必修课程（' + requiredApi.length + ' 门） 必须全部通过', sectionTitles[0])
  check('选修 section counts match the API', sectionTitles[1] === '选修课程（' + electiveApi.length + ' 门） 只需学分总和达标，修哪些不限', sectionTitles[1])

  const requiredRows = await rowTexts(page.locator('.plan-courses .section').nth(0))
  const electiveRows = await rowTexts(page.locator('.plan-courses .section').nth(1))
  check('必修 table renders every required course', requiredRows.length === requiredApi.length, 'rows=' + requiredRows.length + ' api=' + requiredApi.length)
  check('选修 table renders every elective course', electiveRows.length === electiveApi.length, 'rows=' + electiveRows.length + ' api=' + electiveApi.length)
  check('all required courses are marked 已通过', requiredRows.every((r) => r.includes('已通过')), requiredRows.join(' | '))
  check('untaken electives are marked 未修读', electiveRows.every((r) => r.includes('未修读')), electiveRows.join(' | '))
  const semText = requiredRows.join(' | ')
  check('suggested semester is humanised (1 -> 大一上)', semText.includes('大一上'), semText)
  check('all 12 plan courses are accounted for', requiredRows.length + electiveRows.length === planApi.courses.length, requiredRows.length + '+' + electiveRows.length)

  // ------------------------------------------------------------------
  // D. student 2023002 -- no training plan (empty branches)
  // ------------------------------------------------------------------
  phase('D. student 2023002 -> plan-less empty states')
  await uiLogin(page, '2023002', '123456')
  const s2Token = await pageToken(page)
  const plan2Api = await apiGet('/training-plan/my', s2Token)
  check('2023002 really has no plan (fixture precondition)', plan2Api.plan === null, JSON.stringify(plan2Api.plan))

  await gotoPage(page, '/training-plan', '.plan-alert')
  const noPlanAlert = norm(await page.locator('.plan-alert .el-alert__title').textContent())
  check(
    'no-plan alert names the major/grade and tells the student what to do',
    noPlanAlert.includes('软件工程') && noPlanAlert.includes('2023') && noPlanAlert.includes('请联系管理员录入'),
    noPlanAlert,
  )
  check('course list card is hidden when there is no plan', (await page.locator('.plan-courses').count()) === 0)
  const noPlanAudit = norm(await page.locator('.credit-progress .el-empty__description').textContent())
  check('credit card explains why it cannot compute', noPlanAudit.includes('暂无培养计划'), noPlanAudit)

  await gotoPage(page, '/gpa', '.main-value')
  const gpa2 = norm(await page.locator('.main-value').textContent())
  check('2023002 GPA differs from 2023001 (data is per-student)', gpa2 !== gpaText && gpa2.length > 0, 'gpa2=' + gpa2)

  // ------------------------------------------------------------------
  // E. admin -- /training-plan-manage
  // ------------------------------------------------------------------
  phase('E. admin -> /training-plan-manage (list + drawer + form)')
  await uiLogin(page, 'admin01', '123456')
  const adminToken = await pageToken(page)
  const plansApi = await apiGet('/training-plan', adminToken)

  await gotoPage(page, '/training-plan-manage', '.manage-page .el-table__body-wrapper tbody tr')
  const title = norm(await page.locator('.manage-page .card-title').first().textContent())
  check('page title renders', title === '培养计划维护', title)

  const listScope = page.locator('.manage-page .el-card').first()
  const planRows = await rowTexts(listScope)
  check('list renders every plan from the API', planRows.length === plansApi.length, 'rows=' + planRows.length + ' api=' + plansApi.length)
  const seeded = plansApi.find((p) => p.id === 1)
  const seededRow = planRows.find((r) => r.includes(seeded.planName))
  check('seeded plan row is rendered', !!seededRow, planRows.join(' | '))
  check(
    'row shows major, grade and the three credit figures',
    !!seededRow &&
      seededRow.includes(seeded.majorName) &&
      seededRow.includes(seeded.grade) &&
      seededRow.includes(String(seeded.totalCredits)) &&
      seededRow.includes(String(seeded.requiredCredits)) &&
      seededRow.includes(String(seeded.electiveCredits)),
    seededRow,
  )
  check('row shows the 启用 status tag', !!seededRow && seededRow.includes('启用'), seededRow)

  // filter: an unmatched grade must empty the table
  await page.locator('.filter-bar input[placeholder="年级（如 2023）"]').fill('1999')
  await page.locator('.filter-bar').getByRole('button', { name: '查询' }).click()
  await page.waitForTimeout(1200)
  check('unmatched grade filter empties the table', (await tableRowCount(listScope)) === 0, 'rows=' + (await tableRowCount(listScope)))
  await page.locator('.filter-bar').getByRole('button', { name: '重置' }).click()
  await page.waitForTimeout(1200)
  check('reset restores the full list', (await tableRowCount(listScope)) === plansApi.length, 'rows=' + (await tableRowCount(listScope)))

  // course drawer -- fixture read from the admin detail endpoint (not the student payload)
  const seededDetail = await apiGet('/training-plan/' + seeded.id, adminToken)
  await page.locator('.manage-page .el-table__body-wrapper tbody tr').first().getByRole('button', { name: '课程明细' }).click()
  await page.waitForSelector('.el-drawer .el-table__body-wrapper tbody tr', { timeout: 15000 })
  const drawer = page.locator('.el-drawer')
  const drawerTitle = norm(await drawer.locator('.el-drawer__title').textContent())
  check('drawer is titled with the plan name', drawerTitle.includes(seeded.planName), drawerTitle)
  const drawerRows = await rowTexts(drawer)
  check("drawer lists the plan's course rows", drawerRows.length === seededDetail.courses.length, 'rows=' + drawerRows.length + ' api=' + seededDetail.courses.length)
  const drawerSummary = norm(await drawer.locator('.summary').textContent())
  check('drawer summary totals the course count', drawerSummary.includes('共 ' + seededDetail.courses.length + ' 门'), drawerSummary)
  const creditSum = seededDetail.courses.reduce((s, c) => s + Number(c.credit), 0)
  check('drawer summary sums the credits', drawerSummary.includes(creditSum.toFixed(1)), drawerSummary + ' expected=' + creditSum.toFixed(1))
  const requiredSum = seededDetail.courses.filter((c) => c.category === 'REQUIRED').reduce((s, c) => s + Number(c.credit), 0)
  const electiveSum = seededDetail.courses.filter((c) => c.category === 'ELECTIVE').reduce((s, c) => s + Number(c.credit), 0)
  check(
    'drawer summary splits required vs elective credits',
    drawerSummary.includes('必修 ' + requiredSum.toFixed(1)) && drawerSummary.includes('选修 ' + electiveSum.toFixed(1)),
    drawerSummary + ' expected required=' + requiredSum.toFixed(1) + ' elective=' + electiveSum.toFixed(1),
  )
  await drawer.locator('.el-drawer__close-btn').click()
  await page.waitForTimeout(800)
  await parkMouse(page)

  // create form validation
  await page.getByRole('button', { name: '新建培养计划' }).click()
  await page.waitForSelector('.el-dialog', { timeout: 15000 })
  const dialog = page.locator('.el-dialog')
  check('create dialog opens with the right title', norm(await dialog.locator('.el-dialog__title').textContent()) === '新建培养计划')
  await dialog.getByRole('button', { name: '保存' }).click()
  await page.waitForTimeout(600)
  const formErrors = (await dialog.locator('.el-form-item__error').allTextContents()).map(norm)
  check('saving an empty form is blocked by validation', formErrors.length >= 3, JSON.stringify(formErrors))
  check('plan name is required', formErrors.includes('方案名称不能为空'), JSON.stringify(formErrors))
  check('major is required', formErrors.includes('请选择专业'), JSON.stringify(formErrors))
  check('grade is required', formErrors.includes('适用年级不能为空'), JSON.stringify(formErrors))
  await dialog.getByRole('button', { name: '取消' }).click()
  await page.waitForTimeout(800)
  const afterCancel = await tableRowCount(listScope)
  check('cancelling created nothing', afterCancel === plansApi.length, 'rows=' + afterCancel)

  // ------------------------------------------------------------------
  // F. menu visibility + route guards
  // ------------------------------------------------------------------
  phase('F. menu visibility and route guards')
  const adminMenu = await menuItems(page)
  check('admin menu offers 培养计划', adminMenu.includes('培养计划'), JSON.stringify(adminMenu))
  check('admin menu does not offer the student-only 我的绩点', !adminMenu.includes('我的绩点'), JSON.stringify(adminMenu))
  check('admin menu does not offer the student-only 我的方案', !adminMenu.includes('我的方案'), JSON.stringify(adminMenu))

  await uiLogin(page, '2023001', '123456')
  const studentMenu = await menuItems(page)
  check('student menu offers 我的方案', studentMenu.includes('我的方案'), JSON.stringify(studentMenu))
  check('student menu offers 我的绩点', studentMenu.includes('我的绩点'), JSON.stringify(studentMenu))
  check('student menu hides 培养计划维护', !studentMenu.includes('培养计划'), JSON.stringify(studentMenu))
  check('student menu hides 学生管理', !studentMenu.includes('学生管理'), JSON.stringify(studentMenu))

  await page.getByRole('menuitem', { name: '我的绩点' }).click()
  await page.waitForURL('**/gpa', { timeout: 15000 })
  await page.waitForSelector('.main-value', { timeout: 20000 })
  check('menu click navigates to /gpa and loads data', (await page.locator('.main-value').count()) === 1)

  await page.goto(BASE + '/training-plan-manage', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('student is redirected away from /training-plan-manage', !page.url().includes('training-plan-manage'), page.url())
  check('admin-only page did not render for the student', (await page.locator('.manage-page').count()) === 0)

  await uiLogin(page, '10001', '123456')
  const teacherMenu = await menuItems(page)
  check('teacher menu hides 我的绩点 (teachers have no GPA feature)', !teacherMenu.includes('我的绩点'), JSON.stringify(teacherMenu))
  check('teacher menu hides 我的方案', !teacherMenu.includes('我的方案'), JSON.stringify(teacherMenu))
  await page.goto(BASE + '/gpa', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('teacher is redirected away from /gpa', !page.url().endsWith('/gpa'), page.url())

  fs.writeFileSync(DIAG, JSON.stringify({ pageErrors, consoleErrors }, null, 2), 'utf8')
  console.log('\n(diagnostics written to .dsh/verify-p1-ui.diag.json)')
  check('no uncaught page errors during the whole run', pageErrors.length === 0, pageErrors.length + ' error(s), see .dsh/verify-p1-ui.diag.json')

  await browser.close()

  console.log('\n========================================')
  console.log('RESULT: PASS=' + pass + '  FAIL=' + fail)
  console.log('========================================')
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('FATAL: ' + (e && e.stack ? e.stack : e))
  process.exit(1)
})
