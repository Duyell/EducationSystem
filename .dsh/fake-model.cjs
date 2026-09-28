/**
 * A deterministic, OpenAI-compatible **fake model** for CI.
 *
 * WHY THIS EXISTS
 *   The backend's chat path (`OpenAiClient` -> OpenAI-compatible /chat/completions) is the spine of
 *   the whole agent: SSE streaming, the tool-call channel, the guardrail, memory persistence. In CI
 *   there is no model at all, so any assertion that needs a *reply* had to be disabled -- which meant
 *   CI only proved "nothing errored", not "the loop works". Worse, that gap bit us for real:
 *   `.dsh/verify-m2-conversations.cjs --no-llm` still sends real chat requests, and with no model the
 *   assistant side is (by design) never persisted, so its "assistant reply is stored" assertion failed
 *   on CI while passing locally with Ollama running.
 *
 *   This stub closes the gap: it speaks exactly the protocol `OpenAiClient` parses, so CI can run the
 *   *strong* assertions (multi-round persistence, guardrail residue, memory echoed back).
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *   It is NOT a model: it does not answer questions. It echoes what it was sent, which is the point --
 *   every reply is deterministic, so a failure means the *plumbing* broke, never "the model felt
 *   different today". Real-model quality (tool routing, faithfulness) stays out of CI on purpose and
 *   is measured by `.dsh/eval-rag.cjs` / `.dsh/eval-p5-tools.cjs` on a machine that has a model.
 *
 * PROTOCOL NOTES (must match OpenAiClient):
 *   - POST <base>/chat/completions, streamed as `data: {json}` lines, terminated by `data: [DONE]`.
 *   - text arrives as choices[0].delta.content; the end is a chunk carrying `finish_reason`.
 *   - tool calls arrive as choices[0].delta.tool_calls[] and need `finish_reason: "tool_calls"`.
 *   - text is streamed **one code point at a time** on purpose: that is how the real client behaves
 *     and it is what once exposed a partial-tag leak in the guardrail (see ToolCallTextGuardTest).
 *
 * TOOL-CALL MODE
 *   Put `[[tool:name]]` or `[[tool:name:{"json":"args"}]]` in the user message and the stub emits that
 *   tool call instead of text. That lets a test drive the whole HITL gate (confirm card -> execute ->
 *   audit) without a model.
 *
 * Usage: node .dsh/fake-model.cjs [--port 11435]
 * Then start the backend with:
 *   AI_BASE_URL=http://127.0.0.1:11435/v1  AI_API_KEY=stub  AI_MODEL=fake-model
 */
const http = require('node:http')

const portArg = process.argv.indexOf('--port')
const PORT = Number(portArg > -1 ? process.argv[portArg + 1] : process.env.FAKE_MODEL_PORT || 11435)

function sse(res, obj) {
  res.write('data: ' + JSON.stringify(obj) + '\n\n')
}

function startReply(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  })
}

/** The deterministic answer: echo the history back, so a broken prompt/memory is visible in the text. */
function replyFor(users) {
  const first = String((users[0] && users[0].content) || '')
  const last = String((users[users.length - 1] && users[users.length - 1].content) || '')
  if (users.length > 1) {
    return `我记得对话里有 ${users.length} 轮提问，你最早问的是「${first}」。`
  }
  return `收到你的问题：「${last}」。`
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || !req.url.includes('/chat/completions')) {
    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end('{"error":"only POST /chat/completions is supported"}')
    return
  }

  let body = ''
  req.on('data', (chunk) => {
    body += chunk
  })
  req.on('end', () => {
    let payload = {}
    try {
      payload = JSON.parse(body)
    } catch {
      /* an unparsable body is itself worth surfacing rather than crashing the stub */
      res.writeHead(400, { 'Content-Type': 'application/json' })
      res.end('{"error":"malformed JSON body"}')
      return
    }

    const messages = Array.isArray(payload.messages) ? payload.messages : []
    const users = messages.filter((m) => m && m.role === 'user')
    const lastUser = String((users[users.length - 1] && users[users.length - 1].content) || '')
    // A real model answers once it has seen the tool result. The marker stays in the conversation
    // history forever, so without this check the stub would re-issue the same call every round until
    // the runtime hit maxIterations (observed while writing this file) -- which tests the iteration
    // guard by accident and everything else not at all.
    const hasToolResult = messages.some((m) => m && m.role === 'tool')

    startReply(res)

    const marker = /\[\[tool:([A-Za-z_][A-Za-z0-9_]*)(?::(\{[\s\S]*?\}))?\]\]/.exec(lastUser)
    if (marker && !hasToolResult) {
      const name = marker[1]
      const args = marker[2] || '{}'
      const half = Math.ceil(args.length / 2)
      // The name/id arrive first, then the arguments in pieces: the real protocol is incremental,
      // and OpenAiClient assembles the argument string from deltas.
      sse(res, {
        choices: [
          { delta: { tool_calls: [{ index: 0, id: 'call_fake_1', type: 'function', function: { name, arguments: '' } }] } },
        ],
      })
      sse(res, { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: args.slice(0, half) } }] } }] })
      sse(res, { choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: args.slice(half) } }] } }] })
      sse(res, { choices: [{ delta: {}, finish_reason: 'tool_calls' }] })
      res.write('data: [DONE]\n\n')
      res.end()
      console.log(`[fake-model] tool call: ${name} ${args}`)
      return
    }

    const reply = replyFor(users)
    for (const ch of reply) {
      sse(res, { choices: [{ delta: { content: ch } }] })
    }
    sse(res, { choices: [{ delta: {}, finish_reason: 'stop' }] })
    res.write('data: [DONE]\n\n')
    res.end()
    console.log(`[fake-model] text reply: ${reply}`)
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[fake-model] listening on http://127.0.0.1:${PORT}/v1 (OpenAI-compatible, deterministic)`)
})
