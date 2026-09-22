/**
 * M2 UI verification: drives the AI page's conversation sidebar in a real Chromium
 * against the live dev server (5173) + backend (8080).
 *
 * What only a browser can prove here:
 *   - the sidebar actually renders the server's conversation list (order included);
 *   - "新对话" really creates a conversation and the URL carries its id (shareable/refreshable);
 *   - sending without an id adopts the server-generated conversation id from the SSE event;
 *   - opening a historical conversation restores the persisted messages into bubbles;
 *   - ANOTHER student's conversation id in the URL is refused and the page falls back
 *     instead of getting stuck (the API script proves the server refuses; this proves the
 *     page handles the refusal);
 *   - deleting the ACTIVE conversation clears the chat area and the URL parameter.
 *
 * A real model call is involved (one short prompt). The assertions are deliberately about
 * STRUCTURE (user message persisted, id adopted, title derived), never about the model's
 * wording: qwen2.5:7b routing is noisy and must not make this script flaky.
 *
 * Run from the repo root with dev server (5173), backend (8080), Redis (6379) up:
 *   node .dsh/verify-m2-ui.cjs
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
const DIAG = path.join(__dirname, 'verify-m2-ui.diag.json')

const STUDENT = { username: '2023001', password: '123456' }
const OTHER = { username: '2023002', password: '123456' }
/** 不需要工具的短问题：让这一轮对话快且稳定（工具路由由 API 脚本负责验证） */
const PROMPT = '只回复两个字：收到'

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
function soft(name, cond, detail) {
  console.log('  [' + (cond ? 'OK  ' : 'WARN') + '] ' + name + (detail ? '  -> ' + detail : ''))
}

const norm = (s) => (s || '').replace(/\s+/g, ' ').trim()
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

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

async function apiLogin(who) {
  const res = await fetch(API + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(who),
  })
  const json = await res.json()
  return json.token
}

async function api(method, pathname, token, body) {
  const res = await fetch(API + pathname, {
    method,
    headers: body
      ? { 'Content-Type': 'application/json', token }
      : { token },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text }
}

async function uiLogin(page, who) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => sessionStorage.clear())
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.getByPlaceholder('请输入用户名').fill(who.username)
  await page.getByPlaceholder('请输入密码').fill(who.password)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL('**/index', { timeout: 20000 })
  await page.waitForSelector('.nav-menu .el-menu-item', { timeout: 15000 })
}

/** 打开 AI 页并等侧栏就绪（`readySelector` 为空时只等容器，不要求有会话） */
async function openAiPage(page, query) {
  const url = BASE + '/ai' + (query || '')
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.conversation-sidebar', { timeout: 20000 })
  await page.waitForTimeout(900)
}

async function sidebarTitles(page) {
  return (await page.locator('.conversation-item .item-title').allTextContents()).map(norm)
}
async function bubbleCount(page) {
  return page.locator('.message-bubble').count()
}
function queryId(page) {
  const u = new URL(page.url())
  return u.searchParams.get('conversationId') || ''
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: resolveChromium() })
  const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } })
  const page = await context.newPage()

  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) =>
    pageErrors.push({ phase: currentPhase, url: page.url(), str: String(e && e.stack ? e.stack : e) }),
  )
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(currentPhase + ' @ ' + page.url() + ' :: ' + m.text())
  })

  const tokenB = await apiLogin(OTHER)
  let tokenA = await apiLogin(STUDENT)
  if (!tokenA || !tokenB) {
    console.error('登录失败，无法继续')
    process.exit(2)
  }

  // 准备：先给两个学生的会话**拍快照**（清理阶段只删"快照之外的"，不会误删别人的数据），
  // 再为 A 建两个已知标题的会话、为 B 建一个（越权用例要用）。
  const baselineIds = []
  for (const token of [tokenA, tokenB]) {
    const list = (await api('GET', '/ai/conversations', token)).json?.data
    if (Array.isArray(list)) baselineIds.push(...list.map((c) => c.id))
  }
  const created = []
  const a1 = (await api('POST', '/ai/conversations', tokenA, { title: 'M2-UI-甲' })).json.data
  const a2 = (await api('POST', '/ai/conversations', tokenA, { title: 'M2-UI-乙' })).json.data
  const b1 = (await api('POST', '/ai/conversations', tokenB, { title: 'B-的私密会话' })).json.data
  created.push(a1.id, a2.id, b1.id)

  try {
    // ----------------------------------------------------------------
    phase('A. 侧栏渲染服务端的会话列表（含顺序与选中态）')
    await uiLogin(page, STUDENT)
    // ⚠️ 界面登录会给同一个用户换一个新 token（Redis 里 `token:<username>` 被覆盖），
    // 之前用 API 登录拿到的 tokenA 立刻失效——后续 API 调用必须改用浏览器里这一个。
    // （本仓库踩过：脚本自己并发登录，把正在流式输出的 SSE 连接判定成"登录失效"。）
    tokenA = await page.evaluate(() => sessionStorage.getItem('token'))
    check('界面登录后可取到新 token（用于后续接口核对）', !!tokenA)
    await openAiPage(page)

    const apiList = (await api('GET', '/ai/conversations', tokenA)).json.data
    const titles = await sidebarTitles(page)
    check('侧栏渲染出会话条目', titles.length >= 2, JSON.stringify(titles))
    check(
      '侧栏条数与接口一致（脚本自己建的 2 个都在）',
      titles.includes('M2-UI-甲') && titles.includes('M2-UI-乙'),
      JSON.stringify(titles),
    )
    // 只断言"界面顺序 == 接口顺序"：服务端按 `update_time desc, id desc` 排，
    // 而 update_time 是秒精度——同一秒建的两个会话谁在前由 id 决定（确定性，但不等于"后建的在前"）。
    // 断言"乙必须在甲之前"是**测试自己的错误假设**，不是产品契约。
    check(
      '侧栏顺序与接口返回完全一致',
      JSON.stringify(titles) === JSON.stringify(apiList.map((c) => c.title || '新对话')),
      `ui=${JSON.stringify(titles)} api=${JSON.stringify(apiList.map((c) => c.title))}`,
    )
    check('新对话按钮存在', (await page.getByRole('button', { name: '新对话' }).count()) === 1)
    soft('接口返回条数 = 侧栏条数', titles.length === apiList.length, `api=${apiList.length} ui=${titles.length}`)

    // ----------------------------------------------------------------
    phase('B. 「新对话」真的建会话，并把 id 落到 URL')
    const beforeCount = titles.length
    await page.getByRole('button', { name: '新对话' }).click()
    await page.waitForTimeout(1500)
    let id = queryId(page)
    check('URL 带上 conversationId', UUID_RE.test(id), page.url())
    check('聊天区被清空', (await bubbleCount(page)) === 0, 'bubbles=' + (await bubbleCount(page)))
    const titlesAfterCreate = await sidebarTitles(page)
    check('侧栏多出一个会话', titlesAfterCreate.length === beforeCount + 1, `${beforeCount} -> ${titlesAfterCreate.length}`)
    const listNow = (await api('GET', '/ai/conversations', tokenA)).json.data
    check('新会话在服务端确实存在', listNow.some((c) => c.id === id), 'id=' + id)
    created.push(id)

    // ----------------------------------------------------------------
    phase('C. 不带 id 直接提问：服务端兜底新建并通过 SSE 回传 id')
    await page.goto(BASE + '/ai', { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.conversation-sidebar', { timeout: 20000 })
    await page.waitForTimeout(900)
    check('清掉 URL 参数后处于「新对话」态', queryId(page) === '', page.url())

    await page.getByPlaceholder('输入你的问题，按 Enter 发送...').fill(PROMPT)
    await page.getByRole('button', { name: '发送' }).click()

    // ① 先等"会话 id 落到 URL"——这才是本阶段要证明的东西（SSE 的 conversation 事件被消费），
    //    它不依赖模型答完，因此比等整轮结束快得多。
    try {
      await page.waitForFunction(
        () => !!new URL(window.location.href).searchParams.get('conversationId'),
        null,
        { timeout: 90000 },
      )
    } catch {
      /* 下面用断言给出可读结论 */
    }
    const adoptedId = queryId(page)
    check('SSE 回传的会话 id 被写进 URL', UUID_RE.test(adoptedId), page.url())
    if (adoptedId) created.push(adoptedId)

    // ② 再等这一轮真正结束（发送按钮恢复可用 = loading 结束），以便看标题与落库结果
    //    ⚠️ waitForFunction(fn, arg, options)：options 必须放**第三个**参数，放第二个会被当成 arg，
    //    于是超时仍是默认 30s——本轮就是这么踩到 "Timeout 30000ms exceeded" 的。
    await page
      .waitForFunction(
        () => {
          const btn = Array.from(document.querySelectorAll('button')).find((b) =>
            (b.textContent || '').includes('发送'),
          )
          return !!btn && !btn.className.includes('is-loading') && !btn.disabled
        },
        null,
        { timeout: 240000 },
      )
      .catch(() => soft('等待本轮对话结束', false, '240s 内发送按钮一直处于 loading（模型可能没返回）'))
    await page.waitForTimeout(1500)

    check('兜底新建的会话与「新对话」建的会话不是同一个', !!adoptedId && adoptedId !== id,
      `created=${id} adopted=${adoptedId}`)

    const rows = (await api('GET', `/ai/conversations/${adoptedId}/messages`, tokenA)).json.data.messages
    check(
      '用户提问已落库（首条是刚发出的原文）',
      rows.length >= 1 && rows[0].role === 'user' && rows[0].content === PROMPT,
      JSON.stringify(rows[0] || null),
    )
    soft('助手回复也落了库（真模型这一轮是否作答）', rows.length >= 2, `messages=${rows.length}`)
    check('用户气泡渲染在页面上', (await bubbleCount(page)) >= 1, 'bubbles=' + (await bubbleCount(page)))

    await page.waitForTimeout(1200)
    const titlesAfterChat = await sidebarTitles(page)
    const convRow = (await api('GET', '/ai/conversations', tokenA)).json.data.find((c) => c.id === adoptedId)
    check(
      '侧栏标题由首条用户消息生成（不再叫「新对话」）',
      !!convRow && convRow.title !== '新对话' && convRow.title.includes('收到'),
      'title=' + (convRow ? convRow.title : '(缺)') + ' ui=' + JSON.stringify(titlesAfterChat.slice(0, 3)),
    )
    soft('侧栏里也能看到这个新标题', titlesAfterChat.some((t) => t.includes('收到')), JSON.stringify(titlesAfterChat))

    // ----------------------------------------------------------------
    phase('D. 刷新带 ?conversationId= 的地址：历史消息回填')
    const restoredBubbles = await (async () => {
      await openAiPage(page, '?conversationId=' + adoptedId)
      return bubbleCount(page)
    })()
    check('刷新后仍在同一个会话（URL 生效）', queryId(page) === adoptedId, page.url())
    check('历史消息被回填成气泡', restoredBubbles >= 1, 'bubbles=' + restoredBubbles)
    const bubbleText = norm(await page.locator('.chat-messages').innerText())
    check('回填的气泡里能看到当初的提问', bubbleText.includes('收到'), bubbleText.slice(0, 200))
    check('当前会话在侧栏高亮', (await page.locator('.conversation-item.active').count()) === 1)

    phase('E. 切换到另一个历史会话：消息区被替换')
    const a1Item = page.locator('.conversation-item').filter({ hasText: 'M2-UI-甲' }).first()
    await a1Item.click()
    await page.waitForTimeout(1500)
    check('URL 跟着切到被点击的会话', queryId(page) === a1.id, `url=${page.url()} want=${a1.id}`)
    check('空会话切过去后聊天区是空的（不是上个会话的内容）', (await bubbleCount(page)) === 0, 'bubbles=' + (await bubbleCount(page)))

    // ----------------------------------------------------------------
    phase('F. 打开别人的会话 id：拒绝 + 回落新对话（不把用户卡死）')
    await openAiPage(page, '?conversationId=' + b1.id)
    check('越权会话的 id 被从 URL 上摘掉（回落到新对话）', queryId(page) === '', page.url())
    check('聊天区已清空', (await bubbleCount(page)) === 0, 'bubbles=' + (await bubbleCount(page)))
    check('侧栏仍可用（新对话按钮还在）', (await page.getByRole('button', { name: '新对话' }).count()) === 1)
    const refusedB = (await api('GET', `/ai/conversations/${b1.id}/messages`, tokenB)).json
    check('B 的会话没有被这次越权尝试破坏', refusedB.code === '200', JSON.stringify(refusedB).slice(0, 160))

    // ----------------------------------------------------------------
    phase('G. 删除会话（含删除当前会话）')
    await openAiPage(page)
    const beforeDelete = (await sidebarTitles(page)).length
    await page
      .locator('.conversation-item')
      .filter({ hasText: 'M2-UI-甲' })
      .first()
      .locator('.item-delete')
      .click()
    await page.waitForSelector('.el-message-box', { timeout: 10000 })
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await page.waitForTimeout(1800)
    const afterDelete = await sidebarTitles(page)
    check('被删的会话从侧栏消失', !afterDelete.includes('M2-UI-甲'), JSON.stringify(afterDelete))
    check('只少了一个会话', afterDelete.length === beforeDelete - 1, `${beforeDelete} -> ${afterDelete.length}`)
    const listAfterDelete = (await api('GET', '/ai/conversations', tokenA)).json.data
    check('服务端也删掉了', !listAfterDelete.some((c) => c.id === a1.id))
    created.splice(created.indexOf(a1.id), 1)

    // 删除**当前**会话：聊天区清空 + URL 参数消失
    await openAiPage(page, '?conversationId=' + adoptedId)
    check('准备：当前会话已选中', queryId(page) === adoptedId)
    const activeItem = page.locator('.conversation-item.active').first()
    check('准备：侧栏高亮的正是当前会话', (await activeItem.count()) === 1)
    await activeItem.locator('.item-delete').click()
    await page.waitForSelector('.el-message-box', { timeout: 10000 })
    await page.locator('.el-message-box').getByRole('button', { name: '删除' }).click()
    await page.waitForTimeout(1800)
    check('删掉当前会话后 URL 参数被移除', queryId(page) === '', page.url())
    check('删掉当前会话后聊天区清空', (await bubbleCount(page)) === 0, 'bubbles=' + (await bubbleCount(page)))
    created.splice(created.indexOf(adoptedId), 1)

    // ----------------------------------------------------------------
    phase('H. 健康与清理')
    fs.writeFileSync(DIAG, JSON.stringify({ pageErrors, consoleErrors }, null, 2), 'utf8')
    check('整轮没有未捕获的前端异常', pageErrors.length === 0, pageErrors.length + ' 个，见 .dsh/verify-m2-ui.diag.json')
    soft('没有 console.error（Element Plus 的告警也会落这里）', consoleErrors.length === 0, String(consoleErrors.slice(0, 2)))
  } finally {
    /*
     * 清理：本脚本必须**可重复跑且不留数据**。
     *
     * 教训（本轮踩到）：只在 `created` 里登记的会话会被清掉，而脚本中途失败时
     * （比如 phase C 兜底新建的那一个）它还没进 `created`——上一轮就因此把 2 个会话留在了库里，
     * 于是下一轮 phase A 看到的是 4 条。
     * 现在改成"启动时快照 + 结束前删掉快照之外的"，并把删除失败计入判定，
     * 而不是像之前那样只写进详情字符串（条件里不看它，失败也会显示 PASS）。
     */
    const leftoverIds = []
    let cleanupFailed = 0
    // 清理用**新登录**的 token：界面登录会覆盖 Redis 里的 `token:<username>`，启动时那个已经失效
    const freshA = await apiLogin(STUDENT).catch(() => null)
    const freshB = await apiLogin(OTHER).catch(() => null)
    for (const [who, token] of [
      [STUDENT.username, freshA],
      [OTHER.username, freshB],
    ]) {
      if (!token) {
        cleanupFailed++
        continue
      }
      const list = (await api('GET', '/ai/conversations', token)).json?.data
      if (!Array.isArray(list)) {
        cleanupFailed++
        continue
      }
      for (const conv of list) {
        if (baselineIds.includes(conv.id)) continue
        leftoverIds.push(conv.id)
        const res = await api('DELETE', '/ai/conversations/' + conv.id, token)
        if (!(res.json && res.json.code === '200')) cleanupFailed++
      }
      // 再确认一次真的清空了（删除请求成功 ≠ 数据没了）
      const after = (await api('GET', '/ai/conversations', token)).json?.data
      if (!Array.isArray(after)) cleanupFailed++
      else if (after.some((c) => !baselineIds.includes(c.id))) cleanupFailed++
      console.log(`  (清理 ${who}: 本轮新建 ${leftoverIds.length} 个，剩余非基线会话 ` +
        `${Array.isArray(after) ? after.filter((c) => !baselineIds.includes(c.id)).length : '未知'})`)
    }
    check('清理干净：本轮新建的会话都已删除，且没有删除失败', cleanupFailed === 0,
      `删除失败=${cleanupFailed} 本轮新建=${leftoverIds.length}`)
    await browser.close()
  }

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
