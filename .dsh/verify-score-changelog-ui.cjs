/**
 * 成绩变更日志页（管理员）的实机验证。
 *
 * 为什么必须有浏览器这一层：这张表的全部价值在**展示**——"改前 → 改后"到底有没有渲染出来、
 * 改动过的字段有没有被强调、来源（界面/接口 vs AI 助手）看不看得出来。
 * 接口测试（`verify-privacy.ps1` 里已经有 change-log 的断言）只能证明数据对，
 * 证明不了这一页把它讲清楚了；单元测试证明了差异推导，证明不了 Vue 把它渲染出来了。
 *
 * 数据由脚本自己造：以教师身份给 (课程10, 学生2024002) 录一次成绩（INSERT）、改一次（UPDATE）、
 * 最后删掉（DELETE）——三条记录都会留在日志里（**这是设计**：审计记录不可通过界面删除），
 * 所以本脚本的断言一律**不依赖总条数**，只断言"我造的那几条能查到、并且显示正确"。
 *
 * 运行前提：dev server(5173) + 后端(8080) + Redis + MySQL 都在跑。
 *   node .dsh/verify-score-changelog-ui.cjs
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
const DIAG = path.join(__dirname, 'verify-score-changelog-ui.diag.json')
const SHOT = path.join(__dirname, 'verify-score-changelog-ui.png')

/** 夹具：(课程10 CS107, 教师10001) 名下、学生 2024002 在该课没有成绩 */
const COURSE_ID = 10
const STUDENT_ID = '2024002'
const TEACHER = '10001'

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

async function api(method, urlPath, token, body) {
  const res = await fetch(API + urlPath, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON：保留原文给断言用 */
  }
  return { status: res.status, json, text }
}

async function login(username, password) {
  const r = await api('POST', '/login', null, { username, password })
  return r.json && r.json.token ? r.json.token : null
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

/** 表格里的行文本（限定在表格体内，避免把表头/空占位也算进来） */
async function rowTexts(page) {
  return (await page.locator('.el-table__body-wrapper tbody tr').allTextContents()).map(norm)
}

/** 查某课程下该学生的成绩记录（教师的归属校验要求带上 courseId） */
async function findScoreId(token) {
  // ⚠️ 没有 `GET /score/course/{id}` 这种端点：只有分页的 `GET /score?courseId=...`
  // （教师调它必须带 courseId 且课程属于自己，否则 403）。
  // 第一版按想象写了个不存在的端点，于是拿不到 id，PUT/DELETE 双双报"成绩记录不存在"。
  const r = await api('GET', `/score?courseId=${COURSE_ID}&pageSize=100`, token)
  const rows = Array.isArray(r.json && r.json.data && r.json.data.list) ? r.json.data.list : []
  const mine = rows.find((x) => String(x.studentId) === STUDENT_ID)
  return mine ? mine.id : null
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: resolveChromium() })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage()

  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) => pageErrors.push({ phase: currentPhase, str: String(e) }))
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(currentPhase + ' :: ' + m.text())
  })

  // ------------------------------------------------------------------
  phase('0. 造夹具：录成绩（INSERT）→ 改成绩（UPDATE）→ 删成绩（DELETE）')
  const teacherToken = await login(TEACHER, '123456')
  check('教师 ' + TEACHER + ' 登录成功', !!teacherToken)
  if (!teacherToken) throw new Error('教师登录失败，无法造夹具')

  // 先清掉可能残留的同 (课程, 学生) 成绩，保证这是干净的 INSERT
  const leftover = await findScoreId(teacherToken)
  if (leftover) {
    await api('DELETE', `/score/${leftover}`, teacherToken)
  }

  const created = await api('POST', '/score', teacherToken, {
    courseId: COURSE_ID,
    studentId: STUDENT_ID,
    usualScore: 80,
    examScore: 90,
  })
  check('录成绩成功（生成 INSERT 记录）', created.json && created.json.code === '200',
    created.text.slice(0, 160))

  const scoreId = await findScoreId(teacherToken)
  check('能查到刚录入的成绩（并拿到它的 id）', !!scoreId, 'id=' + scoreId)

  const updated = await api('PUT', '/score', teacherToken, {
    id: scoreId,
    courseId: COURSE_ID,
    studentId: STUDENT_ID,
    examScore: 30,
  })
  check('改成绩成功（生成 UPDATE 记录：考试 90 → 30）',
    updated.json && updated.json.code === '200', updated.text.slice(0, 160))

  // 顺便造一条 DELETE：删掉成绩（审计里会留下 DELETE 记录，score 行消失）
  const deleted = await api('DELETE', `/score/${scoreId}`, teacherToken)
  check('删成绩成功（生成 DELETE 记录）', deleted.json && deleted.json.code === '200',
    deleted.text.slice(0, 160))

  // ------------------------------------------------------------------
  phase('1. 接口口径：管理员能查到，且 before/after 都在')
  const adminToken = await login('admin01', '123456')
  check('管理员登录成功', !!adminToken)

  const logResp = await api(
    'GET',
    `/score/change-log?page=1&pageSize=50&studentId=${STUDENT_ID}`,
    adminToken,
  )
  const rows = Array.isArray(logResp.json && logResp.json.data && logResp.json.data.list)
    ? logResp.json.data.list
    : []
  console.log('    该学生的变更记录条数: ' + rows.length)
  check('日志接口返回该学生的记录', rows.length >= 3, 'rows=' + rows.length)
  check('包含 INSERT / UPDATE / DELETE 三种操作',
    ['INSERT', 'UPDATE', 'DELETE'].every((op) => rows.some((r) => r.operation === op)),
    JSON.stringify(rows.map((r) => r.operation)))
  const updateRow = rows.find((r) => r.operation === 'UPDATE')
  check('UPDATE 记录带上了改前改后（考试 90 → 30）',
    !!updateRow && Number(updateRow.beforeExam) === 90 && Number(updateRow.afterExam) === 30,
    JSON.stringify(updateRow))
  check('记录带上了来源字段（界面/接口 或 AI）',
    rows.every((r) => r.source === 'UI' || r.source === 'AI'), JSON.stringify(rows.map((r) => r.source)))
  check('记录带上了扩展展示字段（课程名）', rows.every((r) => !!r.courseName),
    JSON.stringify(rows.map((r) => r.courseName)))

  // ------------------------------------------------------------------
  phase('2. 管理员菜单里有入口，且页面能打开')
  await uiLogin(page, 'admin01', '123456')
  const menu = (await page.locator('.nav-menu .el-menu-item').allTextContents()).map(norm)
  check('管理员菜单含「成绩变更日志」', menu.includes('成绩变更日志'), JSON.stringify(menu))

  await page.goto(BASE + '/score-change-log', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.change-log-table', { timeout: 20000 })
  await page.waitForTimeout(1200)
  check('页面标题渲染出来', (await page.locator('.card-title').allTextContents()).some((t) => norm(t).includes('成绩变更日志')))
  const allRows = await rowTexts(page)
  console.log('    首屏行数: ' + allRows.length)
  check('表格有数据（不带筛选时能看到日志）', allRows.length > 0, 'rows=' + allRows.length)

  // ------------------------------------------------------------------
  phase('3. 筛选：按学号过滤出刚才那三条')
  await page.getByPlaceholder('学号（如 2023001）').fill(STUDENT_ID)
  await page.getByRole('button', { name: '查询' }).click()
  await page.waitForTimeout(1500)
  const filtered = await rowTexts(page)
  console.log('    筛选后行数: ' + filtered.length)
  for (const row of filtered.slice(0, 4)) console.log('      | ' + row.slice(0, 150))
  check('筛选后只剩该学生的记录', filtered.length >= 3, 'rows=' + filtered.length)
  check('每行都属于该学生', filtered.every((t) => t.includes(STUDENT_ID)),
    JSON.stringify(filtered.map((t) => t.slice(0, 40))))

  const joined = filtered.join(' \n ')
  check('页面上看得到「新增」操作', joined.includes('新增'), joined.slice(0, 200))
  check('页面上看得到「修改」操作', joined.includes('修改'), joined.slice(0, 200))
  check('页面上看得到「删除」操作', joined.includes('删除'), joined.slice(0, 200))
  check('页面上看得到"改前 → 改后"的差异（90 → 30）', /90\s*→\s*30/.test(joined),
    joined.slice(0, 300))
  check('页面上看得到来源（界面/接口）', joined.includes('界面/接口'), joined.slice(0, 300))
  check('页面上看得到课程与操作人（不必再自己翻译 id）',
    joined.includes('CS107') && joined.includes(TEACHER), joined.slice(0, 300))
  check('改动过的字段被强调（主色 class）',
    (await page.locator('.diff-part-changed').count()) > 0)

  // 截图放在**有数据**的这一阶段：空表截图看不出这张表到底讲清楚了没有
  await page.screenshot({ path: SHOT, fullPage: true }).catch(() => {})

  // ------------------------------------------------------------------
  phase('4. 只读：这张表不给任何修改入口')
  check('表格里没有编辑按钮', (await page.locator('.el-table button').count()) === 0)
  check('页面上没有任何删除/保存按钮',
    !(await page.locator('.page-card').innerText()).includes('删除成绩'))

  // ------------------------------------------------------------------
  phase('5. 空结果与角色隔离')
  await page.getByPlaceholder('学号（如 2023001）').fill('2099001')
  await page.getByRole('button', { name: '查询' }).click()
  await page.waitForTimeout(1500)
  const emptyRows = await rowTexts(page)
  check('查不存在的学生 → 表格为空', emptyRows.length === 0, 'rows=' + emptyRows.length)
  check('空结果给出了提示文案', (await page.locator('.el-table__empty-text').count()) > 0)

  // 学生与教师访问该页会被路由守卫弹回首页（后端也会 403）
  const stuToken = await login('2023001', '123456')
  const forbidden = await api('GET', '/score/change-log?page=1&pageSize=10', stuToken)
  check('学生调该接口被拒（403）', forbidden.status === 403, 'status=' + forbidden.status)
  const teacherForbidden = await api('GET', '/score/change-log?page=1&pageSize=10', teacherToken)
  check('教师调该接口被拒（403）', teacherForbidden.status === 403,
    'status=' + teacherForbidden.status)

  const studentPage = await context.newPage()
  await studentPage.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await studentPage.evaluate(() => sessionStorage.clear())
  await studentPage.reload({ waitUntil: 'domcontentloaded' })
  await studentPage.getByPlaceholder('请输入用户名').fill('2023001')
  await studentPage.getByPlaceholder('请输入密码').fill('123456')
  await studentPage.getByRole('button', { name: '登录' }).click()
  await studentPage.waitForURL('**/index', { timeout: 20000 })
  await studentPage.goto(BASE + '/score-change-log', { waitUntil: 'domcontentloaded' })
  await studentPage.waitForTimeout(1500)
  check('学生直接访问 URL 会被弹回首页', !studentPage.url().includes('score-change-log'),
    studentPage.url())
  const studentMenu = (await studentPage.locator('.nav-menu .el-menu-item').allTextContents()).map(norm)
  check('学生菜单里没有该入口', !studentMenu.includes('成绩变更日志'), JSON.stringify(studentMenu))
  await studentPage.close()

  // ------------------------------------------------------------------
  phase('6. 健康检查')
  fs.writeFileSync(DIAG, JSON.stringify({ pageErrors, consoleErrors, allRows, filtered }, null, 2), 'utf8')
  console.log('\n(diagnostics: .dsh/verify-score-changelog-ui.diag.json, screenshot .dsh/verify-score-changelog-ui.png)')
  check('全程没有未捕获的页面错误', pageErrors.length === 0, JSON.stringify(pageErrors.slice(0, 2)))
  check('全程没有 console.error', consoleErrors.length === 0, JSON.stringify(consoleErrors.slice(0, 2)))

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
