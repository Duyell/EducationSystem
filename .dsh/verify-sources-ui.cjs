/**
 * M3 source-card UI verification: drives the AI chat page in a real Chromium and proves
 * that the "依据 N 条制度条款" card actually renders under the answer.
 *
 * What only a browser can prove here:
 *   - the SSE event -> Vue state -> DOM path end to end. The card is fed by a `sources`
 *     event that the backend publishes BEFORE the assistant message exists (the sources
 *     event is emitted right after the tool runs, while the assistant bubble is only
 *     created when the next model round emits its first token). A unit test can assert
 *     the event; only the browser can prove the pending-stash ordering survives.
 *   - that the card does not break the surrounding v-if / v-else-if chain in the bubble.
 *
 * Run from the repo root with dev server (5173), backend (8080, AI_RAG_ENABLED=true),
 * Redis and pgvector up:
 *   node .dsh/verify-sources-ui.cjs
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
const DIAG = path.join(__dirname, 'verify-sources-ui.diag.json')
const SHOT = path.join(__dirname, 'verify-sources-ui.png')

/// A policy question: must trigger the server-side forced retrieval (route-level RAG).
const QUESTION = '补考通过以后绩点怎么算'
/// How long to wait for the model to finish answering. Measured on this machine: ~70 s warm
/// for this question (3 clauses injected + a few sentences of answer). The first request after
/// a backend restart also pays Ollama's model load, so allow a lot of headroom.
const ANSWER_TIMEOUT_MS = 300000

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

/**
 * The RAG corpus as docId -> document title, read from the very files the indexer chunks.
 * Used to prove the card only ever names documents that exist (a fabricated file name would
 * not be in this map).
 */
function policyDocs() {
  const dir = path.join(__dirname, '..', 'docs', 'policies')
  const docs = new Map()
  for (const file of fs.readdirSync(dir)) {
    const m = /^(\d+)-(.+)\.md$/.exec(file)
    if (!m) continue
    const raw = fs.readFileSync(path.join(dir, file), 'utf8')
    const heading = /^#\s+(.+)$/m.exec(raw)
    docs.set(m[1], heading ? heading[1].trim() : m[2])
  }
  return docs
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

/** Type the question and click send; the page owns the SSE stream from here. */
async function ask(page, question) {
  const input = page.getByPlaceholder('输入你的问题，按 Enter 发送...')
  await input.click()
  await input.fill(question)
  await page.getByRole('button', { name: '发送' }).click()
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: resolveChromium() })
  const context = await browser.newContext({ viewport: { width: 1500, height: 1000 } })
  const page = await context.newPage()

  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', (e) =>
    pageErrors.push({ phase: currentPhase, url: page.url(), str: String(e && e.stack ? e.stack : e) }),
  )
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(currentPhase + ' @ ' + page.url() + ' :: ' + m.text())
  })

  // ------------------------------------------------------------------
  phase('A. open the AI chat page as a student')
  await uiLogin(page, '2023001', '123456')
  await page.goto(BASE + '/ai', { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.message-bubble, .chat-input-area', { timeout: 20000 })
  await page.waitForTimeout(800)
  check(
    'chat page is reachable and shows an input',
    (await page.getByPlaceholder('输入你的问题，按 Enter 发送...').count()) > 0,
  )

  // ------------------------------------------------------------------
  phase('B. ask a policy question and wait for the answer')
  const sourcesBefore = await page.locator('.sources-card').count()
  check('no source card before asking anything', sourcesBefore === 0, 'n=' + sourcesBefore)

  await ask(page, QUESTION)
  const askedAt = Date.now()

  // Completion signal: the input is disabled while `loading` is true (i.e. while the SSE
  // stream is open) and re-enabled when it closes. That is a far better "done" signal
  // than text stability: the source card is published right after retrieval, i.e. BEFORE
  // the assistant has finished (or even started) its answer, so card presence proves
  // nothing about completion.
  const input = page.getByPlaceholder('输入你的问题，按 Enter 发送...')
  let cardSeen = false
  let answerText = ''
  const deadline = Date.now() + ANSWER_TIMEOUT_MS
  await page.waitForTimeout(2000) // let `loading` flip to true before we start polling
  while (Date.now() < deadline) {
    if ((await page.locator('.sources-card').count()) > 0) cardSeen = true
    answerText = await page
      .locator('.message-bubble.assistant .text-msg')
      .last()
      .innerText()
      .catch(() => '')
    const stillStreaming = await input.isDisabled().catch(() => true)
    if (!stillStreaming && norm(answerText).length > 0) break
    await page.waitForTimeout(1000)
  }
  answerText = norm(answerText)
  const waitedS = Math.round((Date.now() - askedAt) / 1000)
  const stillStreamingAtEnd = await input.isDisabled().catch(() => true)
  console.log(
    '    waited ' + waitedS + ' s; answer length ' + answerText.length +
      '; input still disabled: ' + stillStreamingAtEnd,
  )

  const cards = page.locator('.sources-card')
  const cardCount = await cards.count()
  check('the assistant produced an answer', answerText.length > 0, JSON.stringify(answerText.slice(0, 120)))
  check('a source card is rendered under the answer', cardCount > 0, 'cards=' + cardCount)

  if (cardCount > 0) {
    const card = cards.last()
    const head = norm(await card.locator('.sources-head').innerText())
    const citations = (await card.locator('.source-text').allTextContents()).map(norm)
    const tags = (await card.locator('.el-tag').allTextContents()).map(norm)

    console.log('    head     : ' + head)
    console.log('    citations: ' + JSON.stringify(citations))
    console.log('    tags     : ' + JSON.stringify(tags))

    check('card head states the clause count', /依据\s*\d+\s*条制度条款/.test(head), head)
    check('card lists at least one citation', citations.length > 0)
    check('every citation is non-empty', citations.every((c) => c.length > 0), JSON.stringify(citations))
    check('items and citations have the same length', tags.length === citations.length)

    // The strong assertion: a citation may only name a document that actually exists in the
    // RAG corpus. A fabricated file name (the failure mode this whole M3 work exists to kill)
    // would show up here as a citation whose document title is not in docs/policies/.
    const corpus = policyDocs()
    const realTitles = [...corpus.values()]
    const titleOf = (citation) => realTitles.find((t) => citation.startsWith(t))
    check(
      'every citation names a document that exists in docs/policies/ (no fabricated file name)',
      citations.every((c) => !!titleOf(c)),
      JSON.stringify(citations) + ' vs corpus ' + JSON.stringify(realTitles),
    )
    check('card shows the JW- doc tag', tags.some((t) => /^JW-\d+$/.test(t)), JSON.stringify(tags))

    // Cross-check the tag against the citation: JW-03 must belong to 《重修与补考办法》.
    // The tag and the title come from the same hit metadata, so a mismatch means the card is
    // pairing one clause's text with another clause's id.
    const pairs = []
    for (let i = 0; i < tags.length; i++) {
      const id = /^JW-(\d+)$/.exec(tags[i])?.[1]
      const title = titleOf(citations[i])
      const expected = id ? corpus.get(id) : undefined
      pairs.push({ tag: tags[i], citation: citations[i], expectedTitle: expected })
      check(
        'tag ' + tags[i] + ' matches the cited document',
        !!id && !!title && expected === title,
        JSON.stringify({ tag: tags[i], citedTitle: title, titleOfTag: expected }),
      )
    }
    console.log('    pairs    : ' + JSON.stringify(pairs))

    check(
      'the card sits BELOW the answer text inside the same bubble',
      await card.evaluate((el) => {
        const bubble = el.closest('.message-bubble')
        if (!bubble) return false
        const text = bubble.querySelector('.text-msg')
        if (!text) return false
        return !!(text.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING)
      }),
    )
  }

  await page.screenshot({ path: SHOT, fullPage: true }).catch(() => {})

  // ------------------------------------------------------------------
  phase('C. the card survives a full page reload (history replay, no SSE)')
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.message-bubble', { timeout: 20000 })
  await page.waitForTimeout(1500)
  const afterReload = await page.locator('.sources-card').count()
  // NOTE: sources are NOT persisted (they are a live stream artifact, not part of the stored
  // message), so after a reload the card is expected to be absent. Asserting the *absence*
  // documents the limitation instead of letting it surprise someone later.
  check(
    'after reload the card is gone (sources are not persisted with the message)',
    afterReload === 0,
    'cards=' + afterReload,
  )

  // ------------------------------------------------------------------
  phase('D. health')
  fs.writeFileSync(DIAG, JSON.stringify({ pageErrors, consoleErrors, answerText, cardSeen }, null, 2), 'utf8')
  console.log('\n(diagnostics written to .dsh/verify-sources-ui.diag.json, screenshot .dsh/verify-sources-ui.png)')
  check('no uncaught page errors during the whole run', pageErrors.length === 0, pageErrors.length + ' error(s)')
  check('no console errors during the whole run', consoleErrors.length === 0, consoleErrors.length + ' error(s)')

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
