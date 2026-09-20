/**
 * 聚焦对比：只测「有哪些课程可以选？」这一种问法，新旧描述各跑 5 次。
 *
 * 背景：用户实测发现该问法下模型不调用工具、反而追问"请提供课程名称"。
 * 完整探针（tool-selection-probe.cjs）显示 9 个用例里只有这一种问法会失败，
 * 而「列出所有可选课程」总是成功 —— 说明问题出在该问法的措辞与工具描述的配合，
 * 不是工具本身不可用。
 *
 * 用法：node .dsh/probe-one-case.cjs
 */
const http = require('node:http');

const SYSTEM = `你是一个智能的教务系统助手，帮助学生管理课程、成绩和教学评价。

可用的功能：
- 查看已选课程列表
- 查看成绩
- 选课和退课
- 查看可选课程
- 对教师进行教学评价
- 查看评价状态和已提交的评价

规则：
- 涉及数据修改的操作（选课/退课/评价）会由系统弹出确认卡片，用户确认后才真正执行；
  因此你不需要在文字里再追问"是否确认"，直接调用工具即可
- 用户确认后，如实汇报执行结果；用户取消则说明未做修改，并询问是否需要其他帮助
- 回答简洁明了，数据用表格或列表展示
- 成绩只读不可修改`;

const OLD = {
  name: 'get_course_list',
  description: '查看所有可选课程的列表（支持按名称搜索）',
  parameters: { type: 'object', properties: { courseName: { type: 'string', description: '课程名称（可选，用于搜索）' } } },
};
const NEW = {
  name: 'get_course_list',
  description: '查看可选课程列表。不传 courseName 时返回全部可选课程；用户说"有哪些课能选""列出所有可选课程"时，直接调用本工具且不要追问课程名称。',
  parameters: { type: 'object', properties: { courseName: { type: 'string', description: '课程名称关键词，用于筛选；省略则返回全部' } } },
};

const OTHER_TOOLS = [
  ['get_my_courses', '获取当前学生已选的课程列表', { type: 'object', properties: {} }],
  ['get_my_scores', '获取当前学生的成绩', { type: 'object', properties: {} }],
].map(([name, description, parameters]) => ({ type: 'function', function: { name, description, parameters } }));

const QUESTION = '有哪些课程可以选？';
const RUNS = 5;

function ask(tool) {
  return new Promise((resolve) => {
    const payload = JSON.stringify({
      model: 'qwen2.5:7b',
      stream: false,
      messages: [{ role: 'system', content: SYSTEM }, { role: 'user', content: QUESTION }],
      tools: [{ type: 'function', function: tool }, ...OTHER_TOOLS],
    });
    const req = http.request({
      host: 'localhost', port: 11434, path: '/v1/chat/completions', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) },
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        try {
          const j = JSON.parse(Buffer.concat(chunks).toString('utf8'));
          const m = j.choices[0].message;
          if (m.tool_calls) {
            resolve({ called: true, args: m.tool_calls[0].function.arguments });
          } else {
            resolve({ called: false, text: (m.content || '').replace(/\s+/g, ' ').slice(0, 70) });
          }
        } catch (e) { resolve({ called: false, text: 'ERR ' + e.message }); }
      });
    });
    req.on('error', (e) => resolve({ called: false, text: 'ERR ' + e.message }));
    req.write(payload);
    req.end();
  });
}

async function run(label, tool) {
  console.log(`\n=== ${label} ===`);
  let called = 0, clean = 0;
  for (let i = 1; i <= RUNS; i++) {
    const r = await ask(tool);
    if (r.called) {
      called++;
      // 参数为空或 "" 视为「干净地要全部」；凭空编造课程名视为不干净
      const args = (r.args || '').replace(/\s/g, '');
      const isClean = args === '{}' || args === '{"courseName":""}';
      if (isClean) clean++;
      console.log(`  第${i}次: 调用 get_course_list  参数=${r.args}  ${isClean ? '✓ 要全部' : '✗ 参数可疑'}`);
    } else {
      console.log(`  第${i}次: 未调用工具  ✗  "${r.text}"`);
    }
  }
  console.log(`  → 调用率 ${called}/${RUNS}，其中干净地请求全部 ${clean}/${RUNS}`);
  return { called, clean };
}

(async () => {
  console.log(`问题：「${QUESTION}」  每个变体跑 ${RUNS} 次`);
  const oldR = await run('旧描述（改之前）', OLD);
  const newR = await run('新描述（改之后）', NEW);
  console.log('\n================ 对比 ================');
  console.log(`旧描述: 调用 ${oldR.called}/${RUNS}，干净请求全部 ${oldR.clean}/${RUNS}`);
  console.log(`新描述: 调用 ${newR.called}/${RUNS}，干净请求全部 ${newR.clean}/${RUNS}`);
})();
