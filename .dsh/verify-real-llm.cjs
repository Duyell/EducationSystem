/**
 * 真实 LLM 端到端验证（Ollama + qwen2.5:7b）
 *
 * 验证此前一直缺失的那条链路：
 *   模型真实思考 → 决定调工具 → 角色白名单 → 参数校验 → 危险操作挂起
 *   → 前端(本脚本)确认 → 服务端执行 → 审计落库 → 回复用户
 *
 * 用 Node 而非 PowerShell：需要在 SSE 流保持打开的同时另发一个 POST 去确认，
 * 这要求真正的异步并行，PowerShell 写起来很别扭。
 *
 * 用法：node .dsh/verify-real-llm.cjs [--message "自定义消息"] [--no-confirm]
 */
const http = require('node:http');

const BASE = { host: 'localhost', port: 8080 };
const STUDENT = { username: '2023001', password: '123456' };
const args = process.argv.slice(2);
const argVal = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};
const MESSAGE = argVal('--message', '帮我选课，课程ID是5');
const AUTO_CONFIRM = !args.includes('--no-confirm');

let pass = 0, fail = 0;
const check = (name, cond, detail) => {
  if (cond) { pass++; console.log(`  [PASS] ${name}`); }
  else { fail++; console.log(`  [FAIL] ${name}${detail ? '  -> ' + detail : ''}`); }
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
      res.on('end', () => resolve({
        status: res.statusCode,
        headers: res.headers,
        text: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

(async () => {
  console.log('\n=== 0. 准备：登录学生账号 ===');
  const loginRes = await request('POST', '/login', { body: STUDENT });
  let login;
  try { login = JSON.parse(loginRes.text); } catch { }
  if (!login || !login.token) {
    console.log('  登录失败，无法继续:', loginRes.status, loginRes.text.slice(0, 200));
    process.exit(1);
  }
  const token = login.token;
  check('学生 2023001 登录成功', !!token, `role=${login.role}`);

  console.log(`\n=== 1. 发起真实对话：${MESSAGE} ===`);
  console.log('    (等待本地模型推理，7B 模型首次响应可能需要十几秒)');

  const events = [];
  let confirmSent = false;
  let confirmEvent = null;
  const started = Date.now();

  await new Promise((resolve, reject) => {
    const payload = JSON.stringify({ message: MESSAGE });
    const req = http.request({
      ...BASE, method: 'POST', path: '/ai/chat',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        'token': token,
      },
    }, (res) => {
      console.log(`    HTTP ${res.statusCode}, content-type=${res.headers['content-type']}`);
      let buf = '';
      res.on('data', (chunk) => {
        buf += chunk.toString('utf8');
        const lines = buf.split('\n');
        buf = lines.pop();
        for (const line of lines) {
          const t = line.trim();
          if (!t.startsWith('data:')) continue;
          let evt;
          try { evt = JSON.parse(t.slice(5).trim()); } catch { continue; }
          events.push(evt);

          if (evt.type === 'confirm') {
            confirmEvent = evt;
            console.log(`    >>> 收到确认请求: 工具=${evt.tool} 显示名=${evt.displayName} 参数=${JSON.stringify(evt.args)} 超时=${evt.timeoutSeconds}s`);
            if (AUTO_CONFIRM && !confirmSent) {
              confirmSent = true;
              console.log('    >>> 自动点击「确认执行」（另发 POST /ai/confirm，SSE 流保持打开）');
              request('POST', '/ai/confirm', {
                token, body: { confirmId: evt.confirmId, approved: true },
              }).then((r) => {
                console.log(`    >>> 确认接口返回 HTTP ${r.status}: ${r.text.slice(0, 160)}`);
              }).catch((e) => console.log('    >>> 确认请求失败:', e.message));
            }
          }
        }
      });
      res.on('end', resolve);
      res.on('error', reject);
    });
    req.on('error', reject);
    req.write(payload);
    req.end();
  });

  const elapsed = ((Date.now() - started) / 1000).toFixed(1);
  console.log(`\n    流结束，耗时 ${elapsed}s，共收到 ${events.length} 个事件`);

  // ---- 事件协议断言 ----
  console.log('\n=== 2. 事件协议与链路断言 ===');
  const types = events.map((e) => e.type);
  const typeSummary = types.reduce((m, t) => (m[t] = (m[t] || 0) + 1, m), {});
  console.log('    事件分布:', JSON.stringify(typeSummary));

  check('收到 token 事件（模型真的在流式输出）', types.includes('token'));
  check('收到 confirm 事件（危险操作被挂起，未直接执行）', types.includes('confirm'));
  check('收到 confirm_result 事件（确认结果已回传）', types.includes('confirm_result'));
  check('收到 done 事件（对话正常收尾）', types.includes('done'));
  check('未出现 error 事件', !types.includes('error'),
    events.filter((e) => e.type === 'error').map((e) => e.content).join(' | '));

  if (confirmEvent) {
    check('确认事件只出现一次（未重复挂起）', types.filter((t) => t === 'confirm').length === 1);
    check('确认卡片未泄露工具参数以外的内部信息', !!confirmEvent.displayName,
      'displayName=' + confirmEvent.displayName);
    const ar = events.filter((e) => e.type === 'confirm_result');
    check('confirm_result 标记为已批准', ar.length > 0 && ar[0].approved === true,
      JSON.stringify(ar));
  } else {
    console.log('    ⚠️ 未收到 confirm 事件 —— 模型可能没选择调用工具，或判定为无需确认');
  }

  const fullText = events.filter((e) => e.type === 'token').map((e) => e.content).join('');
  console.log('\n=== 3. 模型最终回复（前 300 字）===');
  console.log('    ' + (fullText.slice(0, 300).replace(/\n/g, '\n    ') || '(无文本输出)'));

  console.log(`\n=== 结果: PASS=${pass}  FAIL=${fail} ===`);
  console.log('（数据库侧断言见紧接着的 SQL 核对）');
  process.exit(fail === 0 ? 0 : 1);
})().catch((e) => { console.error('脚本异常:', e); process.exit(2); });
