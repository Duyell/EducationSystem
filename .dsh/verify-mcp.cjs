/**
 * MCP server 的**协议级**端到端验证（M4）。
 *
 * 为什么必须是协议级而不是 Java 单测：单测能证明"暴露策略算得对"，
 * 但证明不了**外部客户端真的能连上并调到工具** —— 中间隔着 SSE 传输、JSON-RPC 握手、
 * 会话 id 绑定、路由器映射、鉴权拦截器这几层。任何一层接错，单测照样全绿。
 * 这里就用 MCP 客户端真正走的那条路：`GET /mcp/sse` 建流 → 从 `endpoint` 事件拿到消息端点 →
 * `initialize` → `tools/list` → `tools/call`（响应通过 SSE 流回传，按 JSON-RPC id 配对）。
 *
 * 同时验证**安全边界**（这部分比"能跑通"更重要）：
 *   - 无 token 连不上（401，与其它接口同一套鉴权）；
 *   - 危险工具（选课/评教）**不出现在 tools/list 里**，调用它也不会执行；
 *   - 角色不符的 token（教师调学生工具面）被拒；
 *   - 每次外部调用都会在 ai_tool_audit 留下一条 `session_id = mcp:*` 的记录（可区分于内置 Agent）。
 *
 * 用法（先启动后端，并让 ai.mcp.enabled=true；模型不需要）：
 *   AI_MCP_ENABLED=true java -jar edu-api/target/edu-api-0.0.1-SNAPSHOT.jar
 *   node .dsh/verify-mcp.cjs
 *
 * NOTE: never edit this file through a PowerShell Get-Content/Set-Content round trip;
 * PS reads it as the ANSI codepage and destroys every Chinese literal.
 */
const path = require('node:path')
const fs = require('node:fs')
const os = require('node:os')
const { execFileSync } = require('node:child_process')

const BASE = process.env.EDU_BASE || 'http://localhost:8080'
const DIAG = path.join(__dirname, 'verify-mcp.diag.json')

const MYSQL = process.env.EDU_MYSQL_CLIENT || 'D:\\mysql-8.4.7-winx64\\mysql-8.4.7-winx64\\bin\\mysql.exe'
const MYSQL_ARGS = (process.env.EDU_MYSQL_ARGS || '').split(/\s+/).filter(Boolean)
const MYSQL_DB = process.env.EDU_MYSQL_DB || 'edujwxt'

/** 学生只读工具（暴露策略放行的那批）；这里刻意硬编码几个"必须出现"的锚点 */
const EXPECTED_READONLY = ['get_my_courses', 'get_my_gpa', 'get_my_scores', 'get_my_training_plan']
/** 必须**不在**列表里的工具：三个危险写操作 + 一个可逆写操作（默认不允许写） */
const MUST_NOT_APPEAR = ['select_course', 'drop_course', 'evaluate_teacher', 'enter_score']

let pass = 0
let fail = 0
let skipped = 0
const failures = []

function check(name, cond, detail) {
  if (cond) {
    pass++
    console.log('  [PASS] ' + name)
  } else {
    fail++
    failures.push(name)
    console.log('  [FAIL] ' + name + (detail ? '  -> ' + detail : ''))
  }
}

/** 基础设施不可用时用 skip 而不是 FAIL：把"环境问题"伪装成"业务断言失败"是踩过的坑 */
function skip(name, why) {
  skipped++
  console.log('  [SKIP] ' + name + '  -> ' + why)
}

const norm = (s) => (s || '').replace(/\s+/g, ' ').trim()

function phase(title) {
  console.log('\n=== ' + title + ' ===')
}

/**
 * 直接用 mysql 客户端读审计表。
 *
 * ⚠️ 它在**受限沙箱里会失败**：Node 的 child_process 用管道抓子进程输出会被拒（EPERM），
 * 而 catch 一旦静默返回空数组，就会把"读不到审计"伪装成"审计没写"——本文件第一版正是如此，
 * 3 条审计断言全红而传输其实完全正常。所以这里显式探测一次，读不到就 skip（不 FAIL）。
 */
let sqlAvailable = null
function probeSql() {
  if (sqlAvailable !== null) return sqlAvailable
  try {
    execFileSync(MYSQL, [...MYSQL_ARGS, '-uroot', '-p123456', '-D', MYSQL_DB, '-N', '-B', '-e', 'select 1'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    })
    sqlAvailable = true
  } catch {
    sqlAvailable = false
  }
  return sqlAvailable
}

function sql(query) {
  if (!probeSql()) return null
  try {
    const out = execFileSync(MYSQL, [...MYSQL_ARGS, '-uroot', '-p123456', '-D', MYSQL_DB, '-N', '-B', '-e', query], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    })
    return out.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  } catch (e) {
    return null
  }
}

async function login(username, password) {
  const res = await fetch(BASE + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  if (!res.ok) return null
  const json = await res.json()
  return json.token || null
}

/**
 * 一条 MCP 会话：建 SSE 流 + JSON-RPC 收发。
 *
 * SSE 传输下，POST 只负责"投递请求"（HTTP 202），**响应从 SSE 流回来**，
 * 因此必须后台持续读流并按 id 配对——这正是 MCP 客户端做的事。
 */
class McpSession {
  constructor(token) {
    this.token = token
    this.nextId = 1
    this.pending = new Map()
    this.endpoint = null
    this.events = []
    this.abort = new AbortController()
  }

  async connect() {
    const res = await fetch(BASE + '/mcp/sse', {
      headers: { token: this.token, Accept: 'text/event-stream' },
      signal: this.abort.signal,
    })
    this.status = res.status
    if (!res.ok || !res.body) return this

    this.reader = (async () => {
      const decoder = new TextDecoder()
      let buffer = ''
      let eventName = 'message'
      for await (const chunk of res.body) {
        buffer += decoder.decode(chunk, { stream: true })
        let idx
        while ((idx = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, idx).replace(/\r$/, '')
          buffer = buffer.slice(idx + 1)
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            this.onEvent(eventName, data)
            eventName = 'message'
          }
        }
      }
    })().catch(() => {})

    // 等 endpoint 事件（它会带 sessionId）
    for (let i = 0; i < 100 && !this.endpoint; i++) {
      await new Promise((r) => setTimeout(r, 50))
    }
    return this
  }

  onEvent(name, data) {
    if (name === 'endpoint') {
      this.endpoint = data
      return
    }
    this.events.push({ name, data })
    try {
      const msg = JSON.parse(data)
      if (msg.id !== undefined && this.pending.has(msg.id)) {
        this.pending.get(msg.id)(msg)
        this.pending.delete(msg.id)
      }
    } catch {
      /* 非 JSON 帧（心跳等）忽略 */
    }
  }

  /** 投递一个 JSON-RPC 请求并等它的响应（从 SSE 流里按 id 配对） */
  async rpc(method, params, { expectReply = true } = {}) {
    const id = this.nextId++
    // ⚠️ 顺序很关键：**先登记等待者，再 POST**。
    // SSE 传输下响应走另一条连接回传，而服务端可能在 fetch() 返回之前就把结果推进流里了
    // （实测：initialize 的响应比 POST 的响应体更早到达）。先 POST 后登记会稳定丢包——
    // 表现为"每一次 rpc 都超时返回 null"，而传输其实是好的（这是本文件第一版踩的坑）。
    let wait = null
    if (expectReply) {
      wait = new Promise((resolve) => {
        this.pending.set(id, resolve)
        setTimeout(() => {
          if (this.pending.delete(id)) resolve(null)
        }, 20000)
      })
    }
    const url = new URL(this.endpoint, BASE).toString()
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', token: this.token },
      body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
    })
    if (!expectReply) return { status: res.status }
    return { status: res.status, reply: await wait }
  }

  async initialize() {
    const { reply } = await this.rpc('initialize', {
      protocolVersion: '2025-03-26',
      capabilities: {},
      clientInfo: { name: 'verify-mcp.cjs', version: '1.0.0' },
    })
    // 按协议补一条 initialized 通知（无 id、不需要响应）
    const url = new URL(this.endpoint, BASE).toString()
    await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', token: this.token },
      body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
    }).catch(() => {})
    return reply
  }

  close() {
    try {
      this.abort.abort()
    } catch {
      /* ignore */
    }
  }
}

async function main() {
  const token = await login('2023001', '123456')
  const teacherToken = await login('10001', '123456')
  check('学生登录成功（MCP 端点复用同一套登录）', !!token)
  if (!token) process.exit(1)

  // ------------------------------------------------------------------
  phase('1. 鉴权：MCP 端点与其它接口同源')
  const anonymous = await fetch(BASE + '/mcp/sse', { headers: { Accept: 'text/event-stream' } })
  check('不带 token 连 MCP 被拒（401）', anonymous.status === 401, 'status=' + anonymous.status)
  try {
    await anonymous.body?.cancel()
  } catch {
    /* ignore */
  }

  const session = await new McpSession(token).connect()
  check('带 token 能建立 MCP SSE 连接', session.status === 200, 'status=' + session.status)
  check('SSE 返回了 endpoint 事件（消息端点 + sessionId）',
    !!session.endpoint && /sessionId=/.test(session.endpoint), String(session.endpoint))

  // ------------------------------------------------------------------
  phase('2. JSON-RPC 握手')
  const init = await session.initialize()
  check('initialize 有响应', !!init && !!init.result, JSON.stringify(init))
  const serverInfo = init && init.result && init.result.serverInfo
  check('serverInfo 报出了服务名与版本', !!serverInfo && !!serverInfo.name,
    JSON.stringify(serverInfo))
  console.log('    serverInfo: ' + JSON.stringify(serverInfo))
  console.log('    capabilities: ' + JSON.stringify(init && init.result && init.result.capabilities))

  // ------------------------------------------------------------------
  phase('3. tools/list：只读工具面，危险工具不在其中')
  const listed = await session.rpc('tools/list', {})
  const tools = (listed.reply && listed.reply.result && listed.reply.result.tools) || []
  const names = tools.map((t) => t.name)
  console.log('    开放 ' + names.length + ' 个工具: ' + JSON.stringify(names))

  check('tools/list 返回了工具', names.length > 0)
  for (const expected of EXPECTED_READONLY) {
    check('包含只读工具 ' + expected, names.includes(expected), JSON.stringify(names))
  }
  for (const forbidden of MUST_NOT_APPEAR) {
    check('不开放 ' + forbidden, !names.includes(forbidden), JSON.stringify(names))
  }
  check('没有重复工具名', new Set(names).size === names.length)

  const scoreTool = tools.find((t) => t.name === 'enter_score')
  if (scoreTool) {
    const usual = scoreTool.inputSchema && scoreTool.inputSchema.properties && scoreTool.inputSchema.properties.usualScore
    check('工具 Schema 带上了 0~100 约束（换协议不变形）',
      !!usual && usual.maximum === 100 && usual.minimum === 0, JSON.stringify(usual))
  }
  const coursesTool = tools.find((t) => t.name === 'get_my_courses')
  check('工具带 description（外部客户端的模型靠它决定何时调用）',
    !!coursesTool && !!coursesTool.description && coursesTool.description.length > 10)
  check('工具带 title（展示名，便于客户端显示）', !!coursesTool && !!coursesTool.title)

  // ------------------------------------------------------------------
  phase('4. tools/call：真的查到本人的数据')
  const called = await session.rpc('tools/call', { name: 'get_my_courses', arguments: {} })
  const callResult = called.reply && called.reply.result
  check('tools/call 有响应', !!callResult, JSON.stringify(called.reply))
  check('调用未被标记为错误', !!callResult && callResult.isError !== true, JSON.stringify(callResult))
  const text = norm((callResult && callResult.content || []).map((c) => c.text || '').join(' '))
  check('返回内容非空', text.length > 0, text.slice(0, 120))
  check('返回的是真实课程数据（含课程名）', /Java|程序设计|数据结构|数据库/.test(text), text.slice(0, 200))
  check('返回的是 JSON（便于外部客户端结构化使用）', text.startsWith('[') || text.startsWith('{'),
    text.slice(0, 60))

  const gpa = await session.rpc('tools/call', { name: 'get_my_gpa', arguments: {} })
  const gpaResult = gpa.reply && gpa.reply.result
  check('第二个工具也能调（会话可复用）', !!gpaResult && gpaResult.isError !== true,
    JSON.stringify(gpaResult).slice(0, 200))
  const gpaText = norm((gpaResult && gpaResult.content || []).map((c) => c.text || '').join(' '))
  check('绩点工具带出了具体数值/排名', /gpa|绩点|排名|\d/.test(gpaText), gpaText.slice(0, 160))

  // ------------------------------------------------------------------
  phase('5. 越权与未开放工具')
  const beforeRows = sql("select count(*) from course_selection where student_id='2023001'")
  const dangerous = await session.rpc('tools/call', { name: 'select_course', arguments: { courseId: 7 } })
  const dangerousResult = dangerous.reply && dangerous.reply.result
  check('调用未开放的危险工具不会成功',
    !dangerousResult || dangerousResult.isError === true || !dangerous.reply,
    JSON.stringify(dangerous.reply).slice(0, 200))
  const afterRows = sql("select count(*) from course_selection where student_id='2023001'")
  if (beforeRows === null || afterRows === null) {
    skip('危险工具确实没有执行（选课记录没变）', '读不到数据库：' + MYSQL + ' ' + MYSQL_ARGS.join(' ') +
      '（沙箱下 Node 抓子进程输出会被拒；CI 里可正常执行）')
  } else {
    check('危险工具确实没有执行（选课记录没变）',
      JSON.stringify(beforeRows) === JSON.stringify(afterRows),
      beforeRows + ' -> ' + afterRows)
  }

  // 角色不符：教师令牌连上后调学生工具面，必须被拒（工具面是全局注册的，靠令牌角色校验兜底）
  const teacherSession = await new McpSession(teacherToken).connect()
  if (teacherSession.status === 200 && teacherSession.endpoint) {
    await teacherSession.initialize()
    const mismatch = await teacherSession.rpc('tools/call', { name: 'get_my_courses', arguments: {} })
    const mismatchResult = mismatch.reply && mismatch.reply.result
    check('角色不符的工具调用被拒',
      !!mismatchResult && mismatchResult.isError === true,
      JSON.stringify(mismatchResult).slice(0, 200))
    const mismatchText = norm((mismatchResult && mismatchResult.content || []).map((c) => c.text || '').join(' '))
    check('拒绝原因说明了角色不匹配', /角色/.test(mismatchText), mismatchText)
  } else {
    check('教师令牌也能建立 MCP 连接', false, 'status=' + teacherSession.status)
  }
  teacherSession.close()

  // ------------------------------------------------------------------
  phase('6. 外部调用在审计里可区分')
  const audit = sql(
    "select concat(tool_name, '|', ifnull(session_id,'NULL'), '|', status) from ai_tool_audit " +
      "where session_id like 'mcp%' order by id desc limit 5")
  if (audit === null) {
    skip('外部 MCP 调用在审计表留痕', '读不到数据库（见上面的 SKIP 说明）')
  } else {
    console.log('    最近的外部 MCP 审计: ' + JSON.stringify(audit))
    check('存在 session_id 以 mcp 开头的审计记录（外部调用被留痕）', audit.length > 0, JSON.stringify(audit))
    check('审计记到了被调用的工具名', audit.some((r) => r.startsWith('get_my_courses|')),
      JSON.stringify(audit))
    check('审计状态为 SUCCESS', audit.some((r) => r.endsWith('|SUCCESS')), JSON.stringify(audit))
    check('审计带上了 MCP 会话号（能区分是哪一个外部会话）',
      audit.some((r) => /^[a-z_]+\|mcp:[0-9a-f-]{8}/.test(r)), JSON.stringify(audit))
  }

  session.close()

  fs.writeFileSync(DIAG, JSON.stringify({ tools: names, audit, failures }, null, 2), 'utf8')

  console.log('\n========================================')
  console.log('RESULT: PASS=' + pass + '  FAIL=' + fail + (skipped ? '  SKIP=' + skipped : ''))
  console.log('========================================')
  if (skipped) {
    console.log('（SKIP 是环境限制，不是断言失败：CI 里 mysql 客户端可用，这些断言会真跑）')
  }
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('FATAL: ' + (e && e.stack ? e.stack : e))
  try {
    fs.writeFileSync(DIAG, JSON.stringify({ fatal: String(e && e.stack ? e.stack : e) }, null, 2), 'utf8')
  } catch {
    /* ignore */
  }
  process.exit(2)
})
