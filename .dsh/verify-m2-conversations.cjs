/**
 * M2 会话与多轮记忆端到端验证
 *
 * 验证链路：
 *   新建会话 → 带 conversationId 对话（SSE）→ 消息落库 → 历史接口可读
 *   → 第二轮追加（多轮）→ 越权读/删被拒（IDOR）→ 本人删除 → 删后不可读
 *
 * 用 Node 而非 PowerShell：要在 SSE 流保持打开的同时解析事件，
 * 并且这一步要断言"业务错误码/提示语"而不是 HTTP 状态码
 * （本项目未匹配的路径也会返回 HTTP 200，只看状态码会得到假绿）。
 *
 * 前置：后端已启动（8080）、MySQL/Redis 已启动，且已执行
 *   docs/sql/2026-09-22-ai-conversation-migration.sql
 *
 * 用法：node .dsh/verify-m2-conversations.cjs [--no-llm]
 *   --no-llm  跳过真实模型对话（只验证会话 CRUD 与越权），用于模型不可用时快速回归
 */
const http = require('node:http');

const BASE = { host: 'localhost', port: 8080 };
const OWNER = { username: '2023001', password: '123456' };   // 学生
const OTHER = { username: '2023002', password: '123456' };   // 另一个学生
const args = process.argv.slice(2);
const SKIP_LLM = args.includes('--no-llm');

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

/** 发 JSON 请求并解析 Result 包装（code/msg/data） */
async function api(method, path, opts) {
  const res = await request(method, path, opts);
  let json = null;
  try { json = JSON.parse(res.text); } catch { }
  return { status: res.status, json, text: res.text };
}

/** 发起一次 SSE 对话，收集全部事件 */
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

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const textOf = (events) => events.filter((e) => e.type === 'token').map((e) => e.content).join('');

(async () => {
  console.log('\n=== 0. 准备：登录两个学生账号 ===');
  const ownerLogin = await api('POST', '/login', { body: OWNER });
  const otherLogin = await api('POST', '/login', { body: OTHER });
  const ownerToken = ownerLogin.json && ownerLogin.json.token;
  const otherToken = otherLogin.json && otherLogin.json.token;
  if (!ownerToken || !otherToken) {
    console.log('  登录失败，无法继续:', ownerLogin.text.slice(0, 200), otherLogin.text.slice(0, 200));
    process.exit(1);
  }
  check('学生 2023001 / 2023002 均登录成功', true);

  console.log('\n=== 1. 未登录访问会话接口被拒 ===');
  const anon = await api('GET', '/ai/conversations');
  check('无 token 访问 /ai/conversations 返回 401', anon.status === 401, `status=${anon.status}`);
  const badToken = await api('GET', '/ai/conversations', { token: 'not-a-real-token' });
  check('伪造 token 访问被拒（401）', badToken.status === 401, `status=${badToken.status}`);

  console.log('\n=== 2. 新建会话：id 形态与角色来源 ===');
  // 请求体里伪造 role=admin：服务端必须以 token 里的角色为准，否则学生能拿到管理员那套工具
  const created = await api('POST', '/ai/conversations', {
    token: ownerToken, body: { title: 'M2 验证会话', role: 'admin' },
  });
  const conversation = created.json && created.json.data;
  if (!conversation || !conversation.id) {
    console.log('  新建会话失败:', created.text.slice(0, 300));
    process.exit(1);
  }
  const convId = conversation.id;
  check('新建会话返回 UUID 形式的 id', UUID_RE.test(convId), convId);
  check('请求体伪造的 role 被忽略，角色取自 token',
    conversation.role === 'student', `role=${conversation.role}`);
  check('显式标题被保留', conversation.title === 'M2 验证会话', `title=${conversation.title}`);
  check('新会话消息数为 0', conversation.messageCount === 0, `messageCount=${conversation.messageCount}`);

  const list = await api('GET', '/ai/conversations', { token: ownerToken });
  const inList = (list.json && list.json.data || []).some((c) => c.id === convId);
  check('会话出现在本人的会话列表中', inList);

  const otherList = await api('GET', '/ai/conversations', { token: otherToken });
  const leaked = (otherList.json && otherList.json.data || []).some((c) => c.id === convId);
  check('会话不出现在别人的列表中', !leaked);

  console.log('\n=== 3. 带 conversationId 对话（第一轮）===');
  const firstMessage = '我选了几门课？只回答数量。';
  const first = await streamChat(ownerToken, firstMessage, convId);
  const convEvent = first.find((e) => e.type === 'conversation');
  check('SSE 回传 conversation 事件（前端据此绑定会话）', !!convEvent, JSON.stringify(first.slice(0, 2)));
  check('回传的会话 id 与请求一致', !!convEvent && convEvent.conversationId === convId,
    convEvent ? convEvent.conversationId : '(无)');
  if (!SKIP_LLM) {
    check('第一轮对话未出现 error 事件', !first.some((e) => e.type === 'error'),
      first.filter((e) => e.type === 'error').map((e) => e.content).join(' | '));
    check('第一轮对话正常收尾（done）', first.some((e) => e.type === 'done'));
    check('第一轮有文本回答', textOf(first).length > 0);
  }

  console.log('\n=== 4. 消息已落库、历史接口可读 ===');
  const history1 = await api('GET', `/ai/conversations/${convId}/messages`, { token: ownerToken });
  const data1 = history1.json && history1.json.data;
  const messages1 = data1 && data1.messages || [];
  check('历史接口返回会话信息（前端进入历史会话不必再查一次）',
    !!(data1 && data1.conversation && data1.conversation.id === convId));
  check('历史里第一条是刚才发出的用户消息原文',
    messages1.length > 0 && messages1[0].role === 'user' && messages1[0].content === firstMessage,
    JSON.stringify(messages1[0] || null));
  if (!SKIP_LLM) {
    check('历史里第二条是助手回复且非空',
      messages1.length > 1 && messages1[1].role === 'assistant'
        && typeof messages1[1].content === 'string' && messages1[1].content.trim().length > 0,
      JSON.stringify(messages1[1] || null));
    check('消息顺序为 user → assistant（按时间正序）',
      messages1.slice(0, 2).map((m) => m.role).join(',') === 'user,assistant',
      messages1.map((m) => m.role).join(','));
  }
  const afterFirst = await api('GET', '/ai/conversations', { token: ownerToken });
  const row1 = (afterFirst.json.data || []).find((c) => c.id === convId);
  check('会话计数已累加（列表里看得出聊过多少）',
    !!row1 && row1.messageCount >= (SKIP_LLM ? 1 : 2), row1 ? `messageCount=${row1.messageCount}` : '(未找到)');

  console.log('\n=== 5. 第二轮追加（多轮记忆）===');
  const secondMessage = '我刚才第一句话问的是什么？原样重复那句话。';
  const second = await streamChat(ownerToken, secondMessage, convId);
  if (!SKIP_LLM) {
    check('第二轮对话未出现 error 事件', !second.some((e) => e.type === 'error'),
      second.filter((e) => e.type === 'error').map((e) => e.content).join(' | '));
    const secondAnswer = textOf(second);
    // 记忆是否"真的进了模型上下文"由单元测试确定性覆盖（窗口裁剪 + 追加不重复）；
    // 这里只是端到端观察：7B 模型对小问题的复述并不稳定，因此不做 PASS/FAIL。
    soft('模型回答里带上了第一轮的内容（多轮记忆生效，模型侧观察）',
      secondAnswer.includes('几门课') || secondAnswer.includes('选了几门'),
      secondAnswer.slice(0, 120).replace(/\n/g, ' '));
  }
  const history2 = await api('GET', `/ai/conversations/${convId}/messages`, { token: ownerToken });
  const messages2 = history2.json.data.messages || [];
  check('两轮之后历史变长（第二轮的消息也落库了）',
    messages2.length > messages1.length, `${messages1.length} → ${messages2.length}`);
  // 一轮的落库顺序是 user → assistant，所以最后一条是助手回复、倒数第二条才是用户提问
  const lastTwo = messages2.slice(-2);
  check('第二轮的用户提问与助手回复都落库，且顺序为 user → assistant',
    lastTwo.length === 2 && lastTwo[0].role === 'user' && lastTwo[0].content === secondMessage
      && lastTwo[1].role === 'assistant'
      && typeof lastTwo[1].content === 'string' && lastTwo[1].content.trim().length > 0,
    JSON.stringify(lastTwo));

  // 落库正文必须干净：护栏扣下的原始工具调用 JSON / 协议标签不能进会话记录，
  // 否则下一轮模型会把自己上一轮的畸形输出当成"我说过的话"再学一遍。
  // （2026-09-22 就是这条链路抓到"逐字符流式下 </tool_call> 泄漏"的，见 ToolCallTextGuardTest）
  const transcript = messages2.map((m) => m.content || '').join('\n');
  check('落库正文里没有工具调用残渣（原始 JSON 或协议标签）',
    !transcript.includes('"arguments"') && !transcript.includes('tool_call'),
    transcript.slice(0, 200).replace(/\n/g, ' '));

  console.log('\n=== 6. 越权访问（IDOR）===');
  const stolenRead = await api('GET', `/ai/conversations/${convId}/messages`, { token: otherToken });
  check('别人读我的会话被拒（业务码非 200）',
    !!(stolenRead.json && stolenRead.json.code !== '200'), stolenRead.text.slice(0, 200));
  check('拒绝提示不区分"不存在"与"不是你的"（不泄露 id 是否存在）',
    !!(stolenRead.json && stolenRead.json.msg === '会话不存在或无权访问'),
    stolenRead.json ? stolenRead.json.msg : '(无)');

  const unknownId = '00000000-0000-4000-8000-000000000000';
  const unknownRead = await api('GET', `/ai/conversations/${unknownId}/messages`, { token: ownerToken });
  check('不存在的会话与越权会话给出同一句提示（无法探测 id 存在性）',
    !!(unknownRead.json && unknownRead.json.msg === stolenRead.json.msg),
    unknownRead.json ? unknownRead.json.msg : '(无)');

  const stolenDelete = await api('DELETE', `/ai/conversations/${convId}`, { token: otherToken });
  check('别人删我的会话被拒', !!(stolenDelete.json && stolenDelete.json.code !== '200'),
    stolenDelete.text.slice(0, 200));
  const survived = await api('GET', `/ai/conversations/${convId}/messages`, { token: ownerToken });
  check('越权删除失败后会话与消息完好无损',
    !!(survived.json && survived.json.code === '200'
      && survived.json.data.messages.length === messages2.length),
    survived.text.slice(0, 200));

  console.log('\n=== 7. 本人删除 ===');
  const deleted = await api('DELETE', `/ai/conversations/${convId}`, { token: ownerToken });
  check('本人删除成功', !!(deleted.json && deleted.json.code === '200'), deleted.text.slice(0, 200));
  const goneMessages = await api('GET', `/ai/conversations/${convId}/messages`, { token: ownerToken });
  check('删除后历史接口报"会话不存在或无权访问"',
    !!(goneMessages.json && goneMessages.json.code !== '200'
      && goneMessages.json.msg === '会话不存在或无权访问'),
    goneMessages.text.slice(0, 200));
  const finalList = await api('GET', '/ai/conversations', { token: ownerToken });
  check('删除后不再出现在会话列表里',
    !(finalList.json.data || []).some((c) => c.id === convId));

  console.log(`\n=== 结果: PASS=${pass}  FAIL=${fail} ===`);
  if (SKIP_LLM) console.log('（--no-llm：跳过了真实模型对话，仅验证会话 CRUD 与越权）');
  process.exit(fail === 0 ? 0 : 1);
})().catch((e) => { console.error('脚本异常:', e); process.exit(2); });
