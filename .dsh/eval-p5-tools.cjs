/**
 * P5 evaluation: the Agent tool surface, and whether the model actually picks the right tool.
 *
 * Two parts, deliberately separated:
 *
 *   PART 1 — INVENTORY (no LLM, fast, always runs)
 *     Pulls each role's tool list from GET /ai/tools -- i.e. exactly what the model is shown,
 *     read from the live registry rather than a copy pasted into this script (a copy drifts
 *     and then the eval silently stops testing the real thing).
 *     Checks: the expected tools exist per role; risk levels are right; and -- encoding this
 *     repo's documented pitfall as an assertion -- every tool that has OPTIONAL parameters
 *     says in its description what happens when they are omitted.
 *
 *   PART 2 — GOLDEN SET (real closed loop through POST /ai/chat, needs Ollama)
 *     Asks the questions the design doc names as the acceptance criteria ("我学分够毕业吗"
 *     "我该选什么课" "我下周有考试吗") and verifies WHICH tool the model chose, plus that
 *     dangerous tools stop at a confirmation card instead of executing.
 *
 * WHY TOOL NAMES COME FROM THE SSE STREAM
 *   The backend stamps `tool` onto every status/error/confirm event, so the stream itself is the
 *   authoritative record of "which tool did the model pick". This used to be read from the
 *   ai_tool_audit table via the mysql CLI, which was **silently broken in two ways**: mysql on
 *   Windows exits 1 after its password warning (execFileSync throws on that), and -- the real
 *   killer -- the DSH sandbox denies child processes a pipe (`spawnSync ... EPERM`), so the query
 *   returned nothing and every case reported "the model called no tools" while the audit table
 *   held the correct rows. A verification script that cannot fail loudly is worse than none:
 *   this one now reads the live stream, and the persisted-audit cross-check lives in
 *   `.dsh/verify-p5-audit.ps1` where PowerShell's own pipelines (unaffected by the sandbox) do it.
 *
 * Usage:
 *   node .dsh/eval-p5-tools.cjs                  # inventory + golden set
 *   node .dsh/eval-p5-tools.cjs --inventory-only  # skip the LLM part
 *   node .dsh/eval-p5-tools.cjs --only=get_my_gpa,recommend_courses   # iterate on a subset
 *
 * TWO KINDS OF ASSERTION, DELIBERATELY SCORED DIFFERENTLY
 *   1. HARNESS INVARIANTS -- the contract the backend must honour: the role-scoped tool surface,
 *      risk levels, no dangerous execution before confirmation, a cancellation notice after a
 *      decline, the tool actually running on the read-only path, and the OUTPUT GUARDRAIL
 *      (the answer body must never contain raw tool-call JSON -- small models sometimes emit the
 *      call as prose, and the backend now recovers it into a real call instead of showing it).
 *      These must be green; a failure here fails the run.
 *   2. ROUTING ACCURACY -- did the model pick the right tool. That is a property of the MODEL,
 *      not of this code, and it is scored: `ROUTING: n/total`. It is printed loudly and every
 *      miss is named with a diagnosis (asked a clarifying question / emitted the tool call as
 *      text / picked something else). A score below --min-routing (default 0.8) fails the run,
 *      so a real routing regression still turns the build red -- but a 7B model on CPU being
 *      imperfect does not get papered over as "green" either.
 *
 * Requires: backend (8080) + Redis + Ollama (11434) with qwen2.5:7b.
 * Pair with: `.\.dsh\verify-p5-audit.ps1`   (persisted ai_tool_audit cross-check; see its -Mark runbook)
 */
const API = 'http://localhost:8080'
const inventoryOnly = process.argv.includes('--inventory-only')
const onlyArg = process.argv.find((a) => a.startsWith('--only='))
const onlyTools = onlyArg ? onlyArg.slice('--only='.length).split(',').filter(Boolean) : null
const minRouting = Number(
  (process.argv.find((a) => a.startsWith('--min-routing=')) || '--min-routing=0.8').slice(
    '--min-routing='.length,
  ),
)

let pass = 0
let fail = 0
let routingTotal = 0
let routingHits = 0
const routingMisses = []

function check(name, cond, detail) {
  if (cond) {
    pass++
    console.log('  [PASS] ' + name)
  } else {
    fail++
    console.log('  [FAIL] ' + name + (detail ? '  -> ' + detail : ''))
  }
}

async function login(username, password) {
  const r = await fetch(API + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const j = await r.json()
  return j.token
}

async function getTools(token) {
  const r = await fetch(API + '/ai/tools', { headers: { token } })
  const j = await r.json()
  if (j.code !== '200') throw new Error('GET /ai/tools failed: ' + JSON.stringify(j))
  return j.data
}

/**
 * POST /ai/chat and drain the SSE stream; returns the concatenated text and the parsed events.
 * `onEvent` (optional, may be async) runs as each event arrives -- that is what lets us answer a
 * confirmation card the moment it appears instead of waiting out the backend's 180s timeout.
 */
async function chat(token, message, opts = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), opts.timeoutMs || 240000)
  const events = []
  let text = ''
  try {
    const res = await fetch(API + '/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8', token },
      body: JSON.stringify({ message }),
      signal: controller.signal,
    })
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split('\n')
      buf = parts.pop()
      for (const line of parts) {
        const t = line.trim()
        if (!t.startsWith('data:')) continue
        const raw = t.slice(5).trim()
        if (!raw) continue
        let obj
        try {
          obj = JSON.parse(raw)
        } catch {
          continue // keep-alive or a partial frame
        }
        events.push(obj)
        if (obj.type === 'token' && obj.content) text += obj.content
        if (opts.onEvent) await opts.onEvent(obj)
      }
    }
  } finally {
    clearTimeout(timer)
  }
  return { text, events }
}

/** POST /ai/confirm -- the same call the frontend makes when the user clicks 确认 / 取消. */
async function postConfirm(token, confirmId, approved) {
  const r = await fetch(API + '/ai/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8', token },
    body: JSON.stringify({ confirmId, approved }),
  })
  return r.json()
}

/** Every event carrying a tool name (status / error / confirm) -- i.e. what the model chose. */
const eventsWithTool = (events) => events.filter((e) => e.tool)

/**
 * Name why a routing miss happened. Free of judgement, and worth reading twice:
 *   - "emitted the tool call as text" is a MODEL-side failure (qwen2.5:7b does this occasionally):
 *     it wrote the call into the prose instead of using the tool-call channel, so the backend
 *     never saw a call -- and the user got raw JSON on screen. That is a real defect worth
 *     tracking (fix belongs in an output guardrail, not in the tool layer).
 *   - "asked a clarifying question" is usually fixable in the system prompt.
 *   - anything else is "picked a different tool" and needs a look at descriptions.
 */
function diagnose(text, picked) {
  const t = text || ''
  if (/<tool_call>|"name"\s*:\s*"[a-z_]+"\s*,\s*"arguments"/.test(t)) return 'MODEL EMITTED THE TOOL CALL AS TEXT'
  if (picked.length === 0 && /[?？]/.test(t)) return 'ASKED A CLARIFYING QUESTION INSTEAD OF ACTING'
  if (picked.length === 0) return 'NO TOOL CALL AT ALL'
  return 'PICKED A DIFFERENT TOOL (' + picked.join(',') + ')'
}

async function main() {
  console.log('=== PART 1: tool inventory (from the live registry) ===')
  const admin = await login('admin01', '123456')
  const teacher = await login('10001', '123456')
  const student = await login('2023001', '123456')
  check('logins work', !!admin && !!teacher && !!student)

  const studentTools = await getTools(student)
  const teacherTools = await getTools(teacher)
  const adminTools = await getTools(admin)
  const byName = (list) => Object.fromEntries(list.map((t) => [t.name, t]))

  console.log(
    '  inventory: student=' + studentTools.length + ' teacher=' + teacherTools.length + ' admin=' + adminTools.length,
  )

  // ---- the 8 new student tools ----
  const expectedStudent = {
    get_my_training_plan: 'READ_ONLY',
    audit_my_graduation: 'READ_ONLY',
    get_my_gpa: 'READ_ONLY',
    recommend_courses: 'READ_ONLY',
    get_my_exams: 'READ_ONLY',
    get_selection_status: 'READ_ONLY',
    list_my_class_times: 'READ_ONLY',
    check_time_conflict: 'READ_ONLY',
  }
  const sIdx = byName(studentTools)
  for (const [name, risk] of Object.entries(expectedStudent)) {
    check('student tool ' + name + ' is registered', !!sIdx[name], 'missing')
    check('student tool ' + name + ' is ' + risk, sIdx[name] && sIdx[name].riskLevel === risk, sIdx[name] && sIdx[name].riskLevel)
    check('student tool ' + name + ' needs no confirmation', sIdx[name] && sIdx[name].requiresConfirmation === false, String(sIdx[name] && sIdx[name].requiresConfirmation))
  }

  // ---- the 3 new write tools, on the right roles, and DANGEROUS ----
  const tIdx = byName(teacherTools)
  const aIdx = byName(adminTools)
  check('teacher tool submit_course_apply is registered', !!tIdx['submit_course_apply'], 'missing')
  check('submit_course_apply is DANGEROUS', tIdx['submit_course_apply'] && tIdx['submit_course_apply'].riskLevel === 'DANGEROUS', tIdx['submit_course_apply'] && tIdx['submit_course_apply'].riskLevel)
  check('submit_course_apply needs confirmation', tIdx['submit_course_apply'] && tIdx['submit_course_apply'].requiresConfirmation === true, 'false')
  check('teacher tool apply_class_time is registered', !!tIdx['apply_class_time'], 'missing')
  check('apply_class_time is DANGEROUS', tIdx['apply_class_time'] && tIdx['apply_class_time'].riskLevel === 'DANGEROUS', tIdx['apply_class_time'] && tIdx['apply_class_time'].riskLevel)
  check('admin tool approve_course_apply is registered', !!aIdx['approve_course_apply'], 'missing')
  check('approve_course_apply is DANGEROUS', aIdx['approve_course_apply'] && aIdx['approve_course_apply'].riskLevel === 'DANGEROUS', aIdx['approve_course_apply'] && aIdx['approve_course_apply'].riskLevel)

  // ---- role isolation: the new student tools must NOT leak to teacher/admin ----
  const studentOnly = ['audit_my_graduation', 'get_my_gpa', 'recommend_courses', 'get_my_exams', 'get_selection_status']
  check('student-only tools are absent from the teacher list', studentOnly.every((n) => !tIdx[n]), studentOnly.filter((n) => tIdx[n]).join(','))
  check('student-only tools are absent from the admin list', studentOnly.every((n) => !aIdx[n]), studentOnly.filter((n) => aIdx[n]).join(','))
  check('teacher write tools are absent from the student list', !sIdx['submit_course_apply'] && !sIdx['apply_class_time'], 'leaked')
  check('teacher write tools are absent from the admin list', !aIdx['submit_course_apply'] && !aIdx['apply_class_time'], 'leaked')

  // ---- every write-semantic tool must not be READ_ONLY (registry only WARNs; enforce here) ----
  // NOTE: do NOT match a bare "select" -- `get_selection_status` is a read that happens to
  // contain the word, and flagging it is a false positive (this bit me once already).
  const writeWordish = (n) =>
    /insert|update|delete|drop_course|enter|evaluate|apply|approve|submit|select_course/.test(n)
  const allTools = [...studentTools, ...teacherTools, ...adminTools]
  const mislabelled = allTools.filter((t) => writeWordish(t.name) && t.riskLevel === 'READ_ONLY')
  check(
    'no write-semantic tool is left READ_ONLY',
    mislabelled.length === 0,
    mislabelled.map((t) => t.name).join(','),
  )

  // ---- THE PITFALL, AS AN ASSERTION: optional params must document their omission ----
  // A tool with optional parameters whose description never says what omission means makes the
  // model ask a clarifying question instead of acting (this actually happened in this repo).
  const omissionWords = ['省略', '不传', '留空', '默认', '可空', '不填']
  const undocumented = []
  for (const t of allTools) {
    const props = (t.parameters && t.parameters.properties) || {}
    const required = (t.parameters && t.parameters.required) || []
    const optionalCount = Object.keys(props).length - required.length
    if (optionalCount <= 0) continue
    const text = (t.description || '') + ' ' + Object.values(props).map((p) => (p && p.description) || '').join(' ')
    if (!omissionWords.some((w) => text.includes(w))) undocumented.push(t.name + '(' + optionalCount + ')')
  }
  check('every tool with optional params documents what omission does', undocumented.length === 0, undocumented.join(','))

  // ---- descriptions must be real descriptions, not just the tool name echoed back ----
  // Kept deliberately loose: an over-specified keyword list produces false positives on tools
  // whose wording is perfectly fine (e.g. "学生选课，添加课程到已选列表" contains none of the
  // verbs a keyword list would guess). The failure mode worth catching is an EMPTY or
  // name-only description, which is what the model cannot act on.
  const weak = allTools.filter((t) => {
    const d = (t.description || '').trim()
    return d.length < 8 || d === t.name
  })
  check('every tool has a substantive description', weak.length === 0, weak.map((t) => t.name + '="' + (t.description || '') + '"').join(','))

  if (inventoryOnly) {
    console.log('\n(skipping PART 2: --inventory-only)')
    return finish()
  }

  // ==================================================================
  console.log('\n=== PART 2: golden set through the real chat loop (Ollama) ===')

  // A fresh login per case on purpose: it keeps a long run from dying of an expired/invalidated
  // token, and after the async-dispatch fix in LoginInterceptor a re-login can no longer cut off
  // a stream that is mid-flight.
  const USERS = {
    student: { id: '2023001', pass: '123456' },
    teacher: { id: '10001', pass: '123456' },
    admin: { id: 'admin01', pass: '123456' },
  }

  /** ask a question, then read back which tools the stream reported the model choosing */
  async function ask(who, question, expectAny, opts = {}) {
    const token = await login(who.id, who.pass)
    const started = Date.now()
    const onEvent = async (obj) => {
      // Answer the HITL card the instant it appears. Declining is the realistic "user looked at
      // it and said no" path, it is what proves nothing executed, and it releases the backend
      // gate immediately instead of leaving this case blocked on the 180s confirm timeout.
      if (opts.declineConfirm && obj.type === 'confirm' && obj.confirmId) {
        const res = await postConfirm(token, obj.confirmId, false)
        if (res && res.code !== '200') console.log('    (confirm rejected by backend: ' + JSON.stringify(res) + ')')
      }
    }
    const label = question.length > 26 ? question.slice(0, 26) + '…' : question
    let text = ''
    let events = []
    try {
      // Generous budget on purpose: qwen2.5:7b runs on CPU here, the tool surface is 16 tools
      // with a long system prompt, and a cold call also pays the model-load cost -- one measured
      // case took 226s, so a 240s cap was one bad minute away from a false failure.
      const r = await chat(token, question, { timeoutMs: opts.timeoutMs || 300000, onEvent })
      text = r.text
      events = r.events
    } catch (e) {
      // One broken case must not cost the whole run: a stream that dies mid-flight (server
      // restart, aborted socket, invalidated token) is a FAIL for this case, then carry on.
      console.log('  (' + Math.round((Date.now() - started) / 1000) + 's)')
      check('Q: ' + label + ' -> stream completed', false, 'stream error: ' + (e && e.message ? e.message : e))
      return { text: '', picked: [], executed: [], events: [] }
    }
    const elapsed = Math.round((Date.now() - started) / 1000)
    const picked = [...new Set(eventsWithTool(events).map((e) => e.tool))]
    const confirms = events.filter((e) => e.type === 'confirm')
    // The "executing" status is emitted only on the path that really runs the tool, so its
    // presence is the stream-level proof of execution (as opposed to merely being chosen).
    const executed = [
      ...new Set(
        eventsWithTool(events)
          .filter((e) => typeof e.content === 'string' && e.content.indexOf('正在执行') >= 0)
          .map((e) => e.tool),
      ),
    ]
    const ok = expectAny.some((want) => picked.includes(want))
    routingTotal++
    if (ok) routingHits++
    console.log('  (' + elapsed + 's)')
    if (ok) {
      console.log('  [ROUTE] ' + label + ' -> ' + expectAny.join('|'))
    } else {
      // A routing miss is scored, not counted as a harness failure -- see the header. It is
      // still printed in full, and enough of them fail the run via --min-routing.
      const why = diagnose(text, picked)
      routingMisses.push('"' + label + '" expected ' + expectAny.join('|') + ' -- ' + why)
      console.log('  [MISS ] ' + label + ' expected ' + expectAny.join('|') + ' -- ' + why)
      console.log('          picked=[' + picked.join(',') + '] text=' + text.slice(0, 120))
    }
    if (opts.expectConfirm) {
      check('   stopped at a confirmation card (HITL)', confirms.length > 0, 'no confirm event; picked=[' + picked.join(',') + ']')
      const cardTool = confirms.map((c) => c.tool).filter(Boolean)
      check('   the card names ' + expectAny.join('|'), cardTool.some((t) => expectAny.includes(t)), JSON.stringify(cardTool))
      // A dangerous tool must never run before the user confirms -- the stream must show the
      // pause and never the execution that follows it.
      const premature = executed.filter((t) => expectAny.includes(t))
      check('   dangerous tool was NOT executed before confirmation', premature.length === 0, JSON.stringify(premature))
      check('   stream carried a cancellation, not an execution', confirms.length > 0 && premature.length === 0, 'collapsed')
      // After a decline the backend ends the turn ON PURPOSE (the half-finished tool-call chain
      // cannot be sent back to the model), so the closing message is a cancellation status
      // rather than model prose. Asserting prose here would be asserting something the design
      // deliberately does not do -- the first version of this eval did exactly that and reported
      // two false failures.
      const cancelled = events.some(
        (e) => e.type === 'status' && typeof e.content === 'string' && e.content.indexOf('已取消') >= 0,
      )
      check('   closed with an explicit cancellation notice', cancelled, 'no 已取消 status; text=' + text.slice(0, 60))
    } else {
      // Read-only questions must end in an actual natural-language answer, not just a tool call.
      check('   answered with text, not just a tool call', text.trim().length > 0, 'empty answer')
      check('   the chosen tool really ran (not just named)', executed.some((t) => expectAny.includes(t)), 'executed=[' + executed.join(',') + ']')
    }
    // HARNESS INVARIANT (output guardrail, applies to every case): the answer body must never show
    // raw tool-call syntax. Small models occasionally emit the call as PROSE instead of using the
    // tool-call channel; without the guardrail the user sees `{"name": ..., "arguments": ...}` on
    // screen while nothing actually happens. The backend recovers such text into a real call (same
    // whitelist / schema / confirmation gates) and keeps it off the screen -- this assertion is the
    // regression net for that guardrail: it can only go red if the guardrail stops working.
    const rawCallInText = /<tool_call>|<\/tool_call>|\{"name"\s*:\s*"[a-z_]+"\s*,\s*"arguments"/.test(text)
    check('   answer body shows no raw tool-call JSON', !rawCallInText, text.slice(0, 160))
    return { text, picked, executed, events }
  }

  // --- the three acceptance questions from the design doc, plus the rest of the golden set ---
  // Kept as data (not a wall of await calls) so --only= can run a single case while iterating:
  // this set takes ~10 minutes against qwen2.5:7b on CPU, and nobody re-runs that to check one
  // prompt tweak.
  const GOLDEN = [
    { who: 'student', q: '我修了多少学分？够不够毕业？', expect: ['audit_my_graduation', 'get_my_training_plan'] },
    { who: 'student', q: '我的绩点是多少？在专业里排第几？', expect: ['get_my_gpa'] },
    { who: 'student', q: '我该选什么课？帮我推荐一下', expect: ['recommend_courses'] },
    { who: 'student', q: '我下周有什么考试吗？', expect: ['get_my_exams'] },
    { who: 'student', q: '现在能选课吗？', expect: ['get_selection_status'] },
    { who: 'student', q: '我这学期的课表是什么？', expect: ['list_my_class_times'] },
    { who: 'student', q: '我的培养方案里有哪些必修课？', expect: ['get_my_training_plan'] },
    { who: 'student', q: '数据结构与算法这门课和我现在的课表冲突吗？', expect: ['check_time_conflict'] },
    // --- dangerous paths must stop at a card ---
    // NOTE: the utterance has to carry the required fields. `submit_course_apply` needs
    // courseCode/courseName/term/credit/classHour/maxStudent, and the schema validator rejects a
    // call that omits them -- so "我要申请开一门新课" alone makes the model ASK for the fields
    // (correct behaviour!) and never reaches a card. Supplying the data is also the realistic
    // utterance; asking with no data is a different (also valid) path that we are not testing here.
    {
      who: 'teacher',
      q: '帮我申请开一门课：课程代码 CS108，名称编译原理，学期 2024-2025-1，3 学分，48 学时，容量 50 人',
      expect: ['submit_course_apply'],
      expectConfirm: true,
      declineConfirm: true,
    },
    {
      who: 'admin',
      q: '帮我审批编号 1 的开课申请',
      expect: ['approve_course_apply'],
      expectConfirm: true,
      declineConfirm: true,
    },
  ]

  let ran = 0
  for (const c of GOLDEN) {
    if (onlyTools && !c.expect.some((t) => onlyTools.includes(t))) continue
    ran++
    await ask(USERS[c.who], c.q, c.expect, c)
  }
  if (onlyTools) console.log('\n(--only filter: ran ' + ran + ' of ' + GOLDEN.length + ' cases)')

  console.log('\n(persisted-audit cross-check: .\\.dsh\\verify-p5-audit.ps1  -- see its header for the -Mark runbook)')
  return finish()
}

function finish() {
  const pct = routingTotal > 0 ? routingHits / routingTotal : 0
  const routingOk = routingTotal === 0 || pct >= minRouting
  console.log('\n========================================')
  console.log('RESULT: PASS=' + pass + '  FAIL=' + fail + '   (harness invariants)')
  if (routingTotal > 0) {
    console.log(
      'ROUTING: ' + routingHits + '/' + routingTotal + ' (' + Math.round(pct * 100) + '%)' +
        '  threshold=' + minRouting + (routingOk ? ' -> ok' : ' -> BELOW THRESHOLD'),
    )
    for (const m of routingMisses) console.log('  MISS: ' + m)
    if (routingMisses.length === 0 && onlyTools) {
      console.log('  (only ' + routingTotal + ' case(s) ran: --only filter)')
    }
  }
  console.log('========================================')
  process.exit(fail === 0 && routingOk ? 0 : 1)
}

main().catch((e) => {
  console.error('FATAL: ' + (e && e.stack ? e.stack : e))
  process.exit(1)
})
