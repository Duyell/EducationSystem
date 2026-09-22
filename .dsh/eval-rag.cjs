/**
 * M3 RAG 评测：检索质量 + 端到端忠实度（代理指标）
 *
 * 两类指标刻意分开，因为它们回答的是**不同的问题**，混在一起就说不清了：
 *
 *   Part 1 检索指标（Hit@5 / MRR / 关键词命中）：走 `GET /ai/rag/search`
 *          —— 与线上 `search_policy` 工具**同一条检索链路**（同一个 PolicySearchService）。
 *          不经模型：否则每条问题要等一分钟，还把模型噪声混进"检索本身好不好"这个指标里。
 *   Part 2 端到端忠实度（代理）：走真实对话 `POST /ai/chat`
 *          —— 只问少数几条，检查三件事：模型确实调了 search_policy、答案里带**条款里的事实**
 *          （如"60 分"）、并且**注明来源**（文档名/章节）。
 *          ⚠️ 诚实说明：这是"忠实度"的**代理指标**，不是 LLM-as-judge 的严格判定。
 *          7B 模型的措辞不稳定，因此这里只断言"事实与出处"，不断言句式。
 *
 * 用法（需后端 8080 + pgvector + Ollama 都在跑，且 ai.rag.enabled=true）：
 *   node .dsh/eval-rag.cjs [--retrieval-only] [--strict-faithfulness] [--min-hit=0.8] [--min-mrr=0.6]
 */
const http = require('node:http');

const BASE = { host: 'localhost', port: 8080 };
const STUDENT = { username: '2023001', password: '123456' };
const args = process.argv.slice(2);
const argVal = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};
const RETRIEVAL_ONLY = args.includes('--retrieval-only');
const STRICT_FAITHFULNESS = args.includes('--strict-faithfulness');
const MIN_HIT = Number(argVal('--min-hit', '0.75'));
const MIN_MRR = Number(argVal('--min-mrr', '0.5'));
const TOP_K = Number(argVal('--top-k', '5'));

let pass = 0, fail = 0;
const check = (name, cond, detail) => {
  if (cond) { pass++; console.log(`  [PASS] ${name}`); }
  else { fail++; console.log(`  [FAIL] ${name}${detail ? '  -> ' + detail : ''}`); }
};
const soft = (name, cond, detail) => {
  console.log(`  [${cond ? 'OK  ' : 'WARN'}] ${name}${detail ? '  -> ' + detail : ''}`);
};

function request(method, path, { token, body } = {}) {
  return new Promise((resolve, reject) => {
    const payload = body ? JSON.stringify(body) : null;
    const headers = {};
    if (token) headers['token'] = token;
    if (payload) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(payload);
    }
    const req = http.request({ ...BASE, method, path, headers }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function api(method, path, opts) {
  const res = await request(method, path, opts);
  let json = null;
  try { json = JSON.parse(res.text); } catch { /* 非 JSON */ }
  return { status: res.status, json, text: res.text };
}

/**
 * 评测集：每条的 `expect` 是"应该被召回的制度编号"，`keyword` 是条款里必须出现的事实。
 *
 * 允许**多个**可接受文档：例如"补考绩点怎么算"，FAQ（10）与《成绩构成与绩点换算办法》（05）
 * 都算命中——评测不该把"更贴切的 FAQ 排第一"判成错误（上一轮就因为把 top1 钉死在 05 而误报）。
 */
const CASES = [
  { q: '补考通过以后绩点怎么算', expect: ['05', '10', '03'], keyword: '60' },
  { q: '退课之后还能再选这门课吗', expect: ['02', '10'], keyword: '退' },
  { q: '毕业需要多少学分，必修课必须全部通过吗', expect: ['04', '10'], keyword: '学分' },
  { q: '教学评价会让老师知道是谁评的吗', expect: ['08', '10'], keyword: '匿名' },
  { q: '一学期最多能修多少学分', expect: ['01', '04', '02'], keyword: '学分' },
  { q: '考试作弊怎么处理', expect: ['07', '10'], keyword: '作弊' },
  { q: '重修怎么报名，要交钱吗', expect: ['03', '10'], keyword: '重修' },
  { q: '老师修改学生成绩需要什么手续', expect: ['09', '10'], keyword: '成绩' },
  { q: '排课时间冲突是怎么判定的', expect: ['06'], keyword: '冲突' },
  { q: '什么情况下会被学业预警', expect: ['01', '10'], keyword: '学分' },
  { q: '平时成绩和考试成绩各占多少比例', expect: ['05', '09'], keyword: '0.4' },
  { q: '缓考和补考有什么区别', expect: ['07', '03', '10'], keyword: '补考' },
];

/** 取该条问题的"标准答案要点"（用于 Part 2 的忠实度代理断言） */
const ANSWER_CASES = [
  { q: '补考通过以后绩点怎么算？请说明依据的制度文件。', keyword: '60', citeAny: ['成绩构成与绩点换算办法', '常见问题（FAQ）', '重修与补考办法'] },
  { q: '教学评价会显示我的名字吗？请说明依据。', keyword: '匿名', citeAny: ['教学评价规则', '常见问题（FAQ）'] },
];

(async () => {
  const login = await api('POST', '/login', { body: STUDENT });
  const token = login.json && login.json.token;
  if (!token) {
    console.log('登录失败：', login.text.slice(0, 200));
    process.exit(1);
  }

  // ---------------------------------------------------------------
  console.log(`\n=== Part 1. 检索指标（Hit@${TOP_K} / MRR / 关键词命中），共 ${CASES.length} 条 ===`);
  let hitCount = 0, keywordCount = 0, mrrSum = 0;
  const misses = [];
  for (const c of CASES) {
    const res = await api('GET',
      `/ai/rag/search?query=${encodeURIComponent(c.q)}&topK=${TOP_K}`, { token });
    const results = (res.json && res.json.data && res.json.data.results) || [];
    if (results.length === 0) {
      misses.push(`${c.q}（零召回）`);
      continue;
    }
    // 首个"命中期望文档"的排名（1-based）；找不到算 0
    let rank = 0;
    results.forEach((r, i) => {
      if (rank === 0 && c.expect.includes(String(r.docId))) rank = i + 1;
    });
    if (rank > 0) { hitCount++; mrrSum += 1 / rank; } else { misses.push(`${c.q}（期望 ${c.expect}，实际 ${results.map((r) => r.docId)}）`); }

    const joined = results.map((r) => r.text || '').join('\n');
    if (joined.includes(c.keyword)) keywordCount++;
    else misses.push(`${c.q}（命中条款里没有关键词「${c.keyword}」）`);

    const top = results[0];
    console.log(`  · ${c.q}\n      top1 = [${top.docId}] ${top.docTitle} / ${top.section}`);
  }

  const hitRate = hitCount / CASES.length;
  const mrr = mrrSum / CASES.length;
  const keywordRate = keywordCount / CASES.length;
  console.log(`\n  Hit@${TOP_K} = ${hitCount}/${CASES.length} = ${(hitRate * 100).toFixed(1)}%`);
  console.log(`  MRR     = ${mrr.toFixed(3)}`);
  console.log(`  关键词命中率 = ${keywordCount}/${CASES.length} = ${(keywordRate * 100).toFixed(1)}%`);

  check(`Hit@${TOP_K} ≥ ${(MIN_HIT * 100).toFixed(0)}%`, hitRate >= MIN_HIT, `实际 ${(hitRate * 100).toFixed(1)}%`);
  check(`MRR ≥ ${MIN_MRR}`, mrr >= MIN_MRR, `实际 ${mrr.toFixed(3)}`);
  // 关键词命中率是"检索到的条款里真的含有该事实"的证据：比"召回了对的文档"更接近"能答对"
  check('关键词命中率 ≥ 80%', keywordRate >= 0.8, `实际 ${(keywordRate * 100).toFixed(1)}%`);
  if (misses.length) {
    console.log('\n  未达标明细（评测集是活的：这里每一条都值得看一眼是"检索确实差"还是"期望值写得不对"）:');
    misses.forEach((m) => console.log('    - ' + m));
  }

  // ---------------------------------------------------------------
  if (RETRIEVAL_ONLY) {
    console.log(`\n=== 结果: PASS=${pass}  FAIL=${fail}（--retrieval-only，跳过端到端忠实度）===`);
    process.exit(fail === 0 ? 0 : 1);
  }

  console.log(`\n=== Part 2. 端到端忠实度（代理指标），共 ${ANSWER_CASES.length} 条 ===`);
  console.log('    (每条都要过一次真实模型，7B 本机约 30~120s)');
  // ⚠️ 默认**诊断性**：Part 2 断的是"模型措辞与是否产出正文"，而 7B 在这两点上本就不稳定
  //    （实测：检索 100% 命中、工具也调了，但工具结果之后偶发**不产出正文**，答案被截断）。
  //    把这种波动当成硬失败，会让整条评测变成"碰运气"——所以默认只报告、不计入退出码；
  //    里程碑验收时用 --strict-faithfulness 把它升级为门禁。
  let faithfulnessFail = 0;
  const softCheck = (name, cond, detail) => {
    if (cond) { pass++; console.log(`  [PASS] ${name}`); }
    else {
      faithfulnessFail++;
      if (STRICT_FAITHFULNESS) { fail++; console.log(`  [FAIL] ${name}${detail ? '  -> ' + detail : ''}`); }
      else { console.log(`  [WARN] ${name}${detail ? '  -> ' + detail : ''}`); }
    }
  };
  for (const c of ANSWER_CASES) {
    const events = await streamChat(token, c.q);
    const text = events.filter((e) => e.type === 'token').map((e) => e.content).join('');
    const tools = events.filter((e) => e.tool).map((e) => e.tool);
    const calledSearch = tools.includes('search_policy') || events.some((e) => (e.content || '').includes('查询教务制度'));

    softCheck(`「${c.q.slice(0, 12)}…」模型调用了 search_policy`, calledSearch, JSON.stringify([...new Set(tools)]));
    softCheck(`回答里带条款事实「${c.keyword}」`, text.includes(c.keyword), text.slice(0, 160).replace(/\n/g, ' '));
    softCheck('回答里注明了来源（文档名/章节）',
      c.citeAny.some((name) => text.includes(name)),
      `期望提到 ${c.citeAny.join(' / ')}；实际=${text.slice(0, 200).replace(/\n/g, ' ')}`);
  }

  console.log(`\n========================================`);
  console.log(`RESULT: PASS=${pass}  FAIL=${fail}`);
  if (faithfulnessFail > 0) {
    console.log(`⚠️ Part 2 有 ${faithfulnessFail} 项未达标（默认不计入退出码：7B 措辞/空响应本就不稳定）`);
    console.log(`   未达标多数是"模型在工具结果后没产出正文"——这是已知的 7B 弱点，`);
    console.log(`   修复方向：AgentRuntime 对"工具轮之后空响应"重试一次（下一步）。`);
    console.log(`   里程碑验收请用 --strict-faithfulness 让它们变成硬失败。`);
  }
  console.log(`========================================`);
  process.exit(fail === 0 ? 0 : 1);
})().catch((e) => { console.error('脚本异常:', e); process.exit(2); });

/** 发一次真实对话，收集 SSE 事件 */
function streamChat(token, message, conversationId) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(conversationId ? { message, conversationId } : { message });
    const events = [];
    const req = http.request({
      ...BASE, method: 'POST', path: '/ai/chat',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        token,
      },
    }, (res) => {
      let buf = '';
      res.on('data', (chunk) => {
        buf += chunk.toString('utf8');
        const lines = buf.split('\n');
        buf = lines.pop();
        for (const line of lines) {
          const t = line.trim();
          if (!t.startsWith('data:')) continue;
          try { events.push(JSON.parse(t.slice(5).trim())); } catch { /* 半包 */ }
        }
      });
      res.on('end', () => resolve(events));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });
}


