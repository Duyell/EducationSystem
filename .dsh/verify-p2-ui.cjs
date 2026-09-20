/**
 * P2 UI verification: drives the scheduling pages in a real Chromium against the
 * live dev server + backend, and checks the rendered DOM against the API.
 *
 * It walks the whole P2 acceptance path through the UI:
 *   teacher creates a course application -> admin approves it (a course row appears)
 *   -> teacher opens the scheduling dialog -> the LIVE conflict check flags the
 *   seeded CS101 clash and names it -> moving the slot clears the conflict
 *   -> a free room is recommended -> submit -> admin approves -> the timetable shows it.
 *
 * Run from the repo root with dev server (5173), backend (8080) and Redis up:
 *   node .dsh/verify-p2-ui.cjs
 *
 * Diagnostics go to .dsh/verify-p2-ui.diag.json.
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
const DIAG = path.join(__dirname, 'verify-p2-ui.diag.json')

// Fixture identities. The term matters: conflicts are term-scoped, and CS101 (which we
// deliberately clash with) lives in 2024-2025-1.
const CODE = 'VERIFY-P2-UI'
const COURSE_NAME = 'VERIFY P2 UI 课程'
const TERM = '2024-2025-1'
const mysql = 'D:\\mysql-8.4.7-winx64\\mysql-8.4.7-winx64\\bin\\mysql.exe'

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

function cleanupFixtures() {
  const sql =
    "delete from class_time where course_id in (select id from course where course_code like 'VERIFY-P2-UI%'); " +
    "delete from class_time_apply where course_id in (select id from course where course_code like 'VERIFY-P2-UI%'); " +
    "delete from course_apply where course_code like 'VERIFY-P2-UI%'; " +
    "delete from course where course_code like 'VERIFY-P2-UI%';"
  const { execFileSync } = require('node:child_process')
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

/**
 * The token the browser is actually using: a second login rotates `token:<username>`
 * in Redis, which would invalidate the browser session.
 */
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
  await page.waitForTimeout(500)
}

async function menuItems(page) {
  return (await page.locator('.nav-menu .el-menu-item').allTextContents()).map(norm)
}

/** Park the pointer off the header's hover-triggered user dropdown. */
async function parkMouse(page) {
  await page.mouse.move(4, 640)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(250)
}

async function tableRowTexts(scope) {
  const empty = await scope.locator('.el-table__empty-block').count()
  if (empty > 0) return []
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

  cleanupFixtures()

  // ------------------------------------------------------------------
  phase('A. teacher menu + route guards')
  await uiLogin(page, '10001', '123456')
  const teacherMenu = await menuItems(page)
  check('teacher menu offers 开课申请', teacherMenu.includes('开课申请'), JSON.stringify(teacherMenu))
  check('teacher menu hides the student-only 我的绩点', !teacherMenu.includes('我的绩点'), JSON.stringify(teacherMenu))
  check('teacher menu hides the student-only 我的方案', !teacherMenu.includes('我的方案'), JSON.stringify(teacherMenu))
  check('teacher menu hides the admin-only 排课审批', !teacherMenu.includes('排课审批'), JSON.stringify(teacherMenu))
  check('teacher menu hides the admin-only 教室管理', !teacherMenu.includes('教室管理'), JSON.stringify(teacherMenu))

  await page.goto(BASE + '/schedule-approve', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('teacher is bounced off /schedule-approve', !page.url().includes('schedule-approve'), page.url())
  await page.goto(BASE + '/room', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('teacher is bounced off /room', !page.url().endsWith('/room'), page.url())

  // ------------------------------------------------------------------
  phase('B. student cannot reach the scheduling pages')
  await uiLogin(page, '2023001', '123456')
  const studentMenu = await menuItems(page)
  check('student menu does NOT leak 开课申请 (shared-base menu fix)', !studentMenu.includes('开课申请'), JSON.stringify(studentMenu))
  check('student menu has no 排课审批', !studentMenu.includes('排课审批'), JSON.stringify(studentMenu))
  check('student menu has no 教室管理', !studentMenu.includes('教室管理'), JSON.stringify(studentMenu))
  await page.goto(BASE + '/course-apply', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(1500)
  check('student is bounced off /course-apply', !page.url().includes('course-apply'), page.url())

  // ------------------------------------------------------------------
  phase('C. teacher /course-apply renders, timetable shows the seeded CS101')
  await uiLogin(page, '10001', '123456')
  const t1Token = await pageToken(page)
  await gotoPage(page, '/course-apply', '.apply-page .el-card')

  const titles = (await page.locator('.apply-page .card-title').allTextContents()).map(norm)
  check('page renders all three cards', titles.includes('我的开课申请') && titles.includes('我的排课申请') && titles.includes('我的课表'), JSON.stringify(titles))

  const timetableText = norm(await page.locator('.apply-page .el-card').last().innerText())
  const seeded = await apiGet('/class-time/my?term=' + TERM, t1Token)
  const seedy = Array.isArray(seeded) ? seeded : []
  check('teacher timetable contains the seeded CS101 row', seedy.some((c) => c.courseCode === 'CS101'), JSON.stringify(seedy.map((c) => c.courseCode)))
  check('timetable card renders the seeded room name', timetableText.includes('1-101'), timetableText.slice(0, 200))

  // ------------------------------------------------------------------
  phase('D. teacher creates a course application through the form')
  await page.getByRole('button', { name: '新建申请' }).click()
  const formDialog = page.locator('.el-dialog').filter({ hasText: '新建开课申请' })
  await formDialog.waitFor({ state: 'visible', timeout: 15000 })
  await formDialog.getByPlaceholder('如 CS108（同一门课共用同一代码）').fill(CODE)
  await formDialog.getByPlaceholder('如 编译原理').fill(COURSE_NAME)
  await formDialog.getByPlaceholder('如 2025-2026-1').fill(TERM)
  await formDialog.getByRole('button', { name: '提交申请' }).click()
  await formDialog.waitFor({ state: 'hidden', timeout: 15000 })
  await page.waitForTimeout(1200)

  const appliesApi = await apiGet('/course-apply/my', t1Token)
  const mine = appliesApi.find((a) => a.courseCode === CODE)
  check('the application was created through the UI', !!mine, JSON.stringify(appliesApi.map((a) => a.courseCode)))
  check('the new application is PENDING', mine && mine.status === 'PENDING', mine && mine.status)
  check('it is owned by the logged-in teacher', mine && mine.teacherId === '10001', mine && mine.teacherId)

  const firstCard = page.locator('.apply-page .el-card').first()
  const applyRows = await tableRowTexts(firstCard)
  check('the application row is rendered', applyRows.some((r) => r.includes(CODE)), applyRows.join(' | '))
  const pendingRow = applyRows.find((r) => r.includes(CODE))
  check('the row shows the 待审批 status', !!pendingRow && pendingRow.includes('待审批'), pendingRow)
  check('a PENDING application offers no 申请排课 button', !(await firstCard.getByRole('button', { name: '申请排课' }).count()), 'button unexpectedly present')

  // ------------------------------------------------------------------
  phase('E. admin approves the application in the UI')
  await uiLogin(page, 'admin01', '123456')
  const adminToken = await pageToken(page)
  await gotoPage(page, '/schedule-approve', '.el-tabs')

  const tabLabels = (await page.locator('.el-tabs__item').allTextContents()).map(norm)
  check('all three tabs render', tabLabels.includes('开课申请审批') && tabLabels.includes('排课申请审批') && tabLabels.includes('课表总览'), JSON.stringify(tabLabels))

  const panel1 = page.locator('.el-tab-pane').first()
  await page.waitForSelector('.el-tab-pane .el-table__wrapper tbody tr, .el-tab-pane .el-table__body-wrapper tbody tr', { timeout: 15000 })
  const approvalRows = await tableRowTexts(panel1)
  const targetRow = panel1.locator('.el-table__body-wrapper tbody tr').filter({ hasText: CODE })
  check('the pending application is listed for approval', (await targetRow.count()) > 0, approvalRows.join(' | '))
  // The applicant column renders the teacher's NAME, not the工号 -- resolve it from the API
  // rather than hardcoding a name that only exists in this seed dataset.
  const teachers = await apiGet('/teacher/all', adminToken)
  const t1 = (Array.isArray(teachers) ? teachers : []).find((t) => t.teacherId === '10001')
  check('the approval row shows the applicant name', !!t1 && approvalRows.some((r) => r.includes(CODE) && r.includes(t1.teacherName)), approvalRows.find((r) => r.includes(CODE)))
  check('the approval row shows the requested term', approvalRows.some((r) => r.includes(CODE) && r.includes(TERM)), approvalRows.find((r) => r.includes(CODE)))

  await targetRow.getByRole('button', { name: '通过' }).click()
  const confirmBox = page.locator('.el-message-box')
  await confirmBox.waitFor({ state: 'visible', timeout: 15000 })
  await confirmBox.getByRole('button', { name: '通过' }).click()
  await page.waitForTimeout(1800)

  const afterApprove = (await apiGet('/course-apply?status=APPROVED', adminToken)).find((a) => a.courseCode === CODE)
  check('the application is APPROVED after the UI action', !!afterApprove, 'not in APPROVED list')
  check('approval generated a course row', !!(afterApprove && afterApprove.createdCourseId), afterApprove && afterApprove.createdCourseId)
  check('the generated course keeps the course code', !!(afterApprove && afterApprove.createdCourseCode === CODE), afterApprove && afterApprove.createdCourseCode)
  const courseId = afterApprove.createdCourseId

  // ------------------------------------------------------------------
  phase('F. teacher scheduling dialog: live conflict detection')
  await uiLogin(page, '10001', '123456')
  // Re-capture: logging 10001 in again ROTATED token:10001 in Redis, so the token taken in
  // phase C is dead from here on (the interceptor rejects a token that no longer matches).
  const t1TokenF = await pageToken(page)
  await gotoPage(page, '/course-apply', '.apply-page .el-card')
  const card1 = page.locator('.apply-page .el-card').first()
  const approvedRow = card1.locator('.el-table__body-wrapper tbody tr').filter({ hasText: CODE })
  await approvedRow.getByRole('button', { name: '申请排课' }).click()

  const schedDialog = page.locator('.el-dialog').filter({ hasText: '申请排课' })
  await schedDialog.waitFor({ state: 'visible', timeout: 15000 })
  // Default slot is Monday 1-2 / weeks 1-16, which collides with the seeded CS101
  // (same teacher, same term) -> the dialog must say so, and name the clashing course.
  await page.waitForTimeout(2000)

  let conflictAlert = schedDialog.locator('.conflict-alert')
  let conflictText = norm(await conflictAlert.innerText())
  check('conflict alert is shown for the default slot', (await conflictAlert.count()) > 0, 'no alert')
  check('the alert is the error variant', (await schedDialog.locator('.conflict-alert.el-alert--error').count()) > 0, conflictText)
  check('the alert reports a conflict count', conflictText.includes('检测到') && conflictText.includes('冲突'), conflictText)
  check('the conflict names the clashing course CS101', conflictText.includes('CS101'), conflictText)
  check('the alert groups the conflict by teacher vs room', conflictText.includes('教师时间冲突'), conflictText)

  const freeRoom = schedDialog.locator('.free-room')
  check('the free-room block renders for the slot', (await freeRoom.count()) === 1)
  check('the free-room block states a recommendation or a reason', norm(await freeRoom.innerText()).length > 0, norm(await freeRoom.innerText()))

  // Move to Monday 3-4: adjacent to CS101's 1-2, so the conflict must clear.
  const spins = schedDialog.getByRole('spinbutton')
  check('the dialog exposes four number inputs (periods + weeks)', (await spins.count()) === 4, 'count=' + (await spins.count()))
  await spins.nth(0).fill('3')
  await spins.nth(0).press('Tab')
  await spins.nth(1).fill('4')
  await spins.nth(1).press('Tab')
  await page.waitForTimeout(2200)

  conflictAlert = schedDialog.locator('.conflict-alert')
  conflictText = norm(await conflictAlert.innerText())
  check('adjacent slot clears the conflict (success branch)', (await schedDialog.locator('.conflict-alert.el-alert--success').count()) > 0, conflictText)
  check('the success text says 无冲突', conflictText.includes('无冲突'), conflictText)

  const recommendBtn = schedDialog.getByRole('button', { name: '用推荐教室' })
  check('the recommend-room button is enabled when a room is free', await recommendBtn.isEnabled())
  await recommendBtn.click()
  await page.waitForTimeout(1500)
  const recommendText = norm(await schedDialog.locator('.free-room').innerText())
  check('a free room was recommended for the cleared slot', recommendText.includes('推荐'), recommendText)

  await schedDialog.getByRole('button', { name: '提交排课申请' }).click()
  await schedDialog.waitFor({ state: 'hidden', timeout: 15000 })
  await page.waitForTimeout(1500)

  const mySchedule = await apiGet('/class-time/apply/my', t1TokenF)
  const schedApply = mySchedule.find((a) => a.courseId === courseId)
  check('the schedule application was created', !!schedApply, JSON.stringify(mySchedule.map((a) => a.courseId)))
  check('it is PENDING with no conflict recorded', schedApply && schedApply.status === 'PENDING' && !schedApply.conflictInfo, JSON.stringify(schedApply))
  check('the chosen room was submitted', !!(schedApply && schedApply.roomId), schedApply && schedApply.roomId)

  const scheduleCard = page.locator('.apply-page .el-card').nth(1)
  await scheduleCard.getByRole('button', { name: '刷新' }).click()
  await page.waitForTimeout(1500)
  const schedRows = await tableRowTexts(scheduleCard)
  check('the schedule application row is rendered', schedRows.some((r) => r.includes(CODE)), schedRows.join(' | '))

  // ------------------------------------------------------------------
  phase('G. admin approves the schedule application -> timetable row')
  await uiLogin(page, 'admin01', '123456')
  // Re-capture for the same reason as phase F: this login rotated token:admin01.
  const adminTokenG = await pageToken(page)
  await gotoPage(page, '/schedule-approve', '.el-tabs')
  await page.locator('.el-tabs__item').filter({ hasText: '排课申请审批' }).click()
  await page.waitForTimeout(1800)

  const schedulePanel = page.locator('.el-tab-pane').nth(1)
  const schedApproveRow = schedulePanel.locator('.el-table__body-wrapper tbody tr').filter({ hasText: CODE })
  check('the schedule application is listed for approval', (await schedApproveRow.count()) > 0, (await tableRowTexts(schedulePanel)).join(' | '))
  check('the approval row reports 无冲突 for the cleared slot', (await schedApproveRow.innerText()).includes('无冲突'), norm(await schedApproveRow.innerText()))

  await schedApproveRow.getByRole('button', { name: '通过' }).click()
  const approveDialog = page.locator('.el-dialog').filter({ hasText: '审批通过排课申请' })
  await approveDialog.waitFor({ state: 'visible', timeout: 15000 })
  check('the approve dialog opens', (await approveDialog.count()) === 1)
  await approveDialog.getByRole('button', { name: '确认通过' }).click()
  await approveDialog.waitFor({ state: 'hidden', timeout: 15000 })
  await page.waitForTimeout(1800)

  const timetable = await apiGet('/class-time/course/' + courseId, adminTokenG)
  check('approval created exactly one timetable row', timetable.length === 1, 'rows=' + timetable.length)
  check('the timetable row carries the approved slot', timetable[0] && timetable[0].weekday === 1 && timetable[0].startPeriod === 3 && timetable[0].endPeriod === 4, JSON.stringify(timetable[0]))
  check('the timetable row carries a room', !!(timetable[0] && timetable[0].roomId), JSON.stringify(timetable[0]))

  await page.locator('.el-tabs__item').filter({ hasText: '课表总览' }).click()
  await page.waitForTimeout(1800)
  const overviewPanel = page.locator('.el-tab-pane').nth(2)
  const overviewRows = await tableRowTexts(overviewPanel)
  check('the timetable overview lists the new course', overviewRows.some((r) => r.includes(CODE)), overviewRows.join(' | '))

  // ------------------------------------------------------------------
  phase('H. admin room management (800 rooms)')
  await gotoPage(page, '/room', '.page-box')
  const roomTitle = norm(await page.locator('.card-title').first().textContent())
  check('room page title renders', roomTitle === '教室维护', roomTitle)

  const roomTable = page.locator('.el-table').first()
  const roomRows = await tableRowTexts(roomTable)
  check('first page shows 20 rooms', roomRows.length === 20, 'rows=' + roomRows.length)
  check('room rows show a generated display name', roomRows.some((r) => /\d+-\d+/.test(r)), roomRows[0])

  const totalText = norm(await page.locator('.page-box').innerText())
  check('pagination reports 800 rooms in total', totalText.includes('800'), totalText)

  await page.getByPlaceholder('最小容量').fill('240')
  await page.getByRole('button', { name: '查询' }).click()
  await page.waitForTimeout(1500)
  const filteredTotal = norm(await page.locator('.page-box').innerText())
  check('capacity filter narrows the total to 80', filteredTotal.includes('80'), filteredTotal)
  const filteredRows = await tableRowTexts(roomTable)
  check('filtered rows all meet the capacity floor', filteredRows.every((r) => !/\b(60|80|100|120|140|160|180|200|220)\b/.test(r)), filteredRows[0])

  await page.getByRole('button', { name: '重置' }).click()
  await page.waitForTimeout(1500)
  check('reset restores all 800 rooms', norm(await page.locator('.page-box').innerText()).includes('800'))

  // ------------------------------------------------------------------
  phase('I. cleanup + health')
  cleanupFixtures()
  const leftover = await apiGet('/course?page=1&pageSize=200', adminTokenG)
  check('fixture course removed', !leftover.list.some((c) => c.courseCode === CODE), 'still present')
  const seededAfter = await apiGet('/class-time/course/1', adminTokenG)
  check('seeded CS101 timetable untouched', seededAfter.length === 1, 'rows=' + seededAfter.length)

  fs.writeFileSync(DIAG, JSON.stringify({ pageErrors, consoleErrors }, null, 2), 'utf8')
  console.log('\n(diagnostics written to .dsh/verify-p2-ui.diag.json)')
  check('no uncaught page errors during the whole run', pageErrors.length === 0, pageErrors.length + ' error(s), see .dsh/verify-p2-ui.diag.json')

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
