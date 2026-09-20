/**
 * 工具选择基线测试：同一批自然语言问题，逐个问本地模型，记录它选了哪个工具、传了什么参数。
 * 用法：node .dsh/tool-selection-probe.cjs
 * 目的：在改工具描述前后各跑一次，用数据说明改动是否有效（而非凭感觉）。
 */
const http = require('node:http');

// 与 AiChatService 中学生角色的 system prompt 保持一致
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

// 从 StudentToolRegistrar 抄一份当前工具定义
// 用 --variant old|new 切换描述版本，用于对比「描述更明确」是否真的改善了模型行为。
const VARIANT = (() => {
  const i = process.argv.indexOf('--variant');
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : 'new';
})();

const OLD_DESC = {
  get_course_list: ['查看所有可选课程的列表（支持按名称搜索）',
    { type: 'object', properties: { courseName: { type: 'string', description: '课程名称（可选，用于搜索）' } } }],
  list_courses: ['查询课程列表',
    { type: 'object', properties: { courseName: { type: 'string', description: '课程名称搜索（可选）' } } }],
};
const NEW_DESC = {
  get_course_list: ['查看可选课程列表。不传 courseName 时返回全部可选课程；用户说"有哪些课能选""列出所有可选课程"时，直接调用本工具且不要追问课程名称。',
    { type: 'object', properties: { courseName: { type: 'string', description: '课程名称关键词，用于筛选；省略则返回全部' } } }],
  list_courses: ['查询课程列表。省略 courseName 时返回全部课程（最多50条）。',
    { type: 'object', properties: { courseName: { type: 'string', description: '课程名称关键词；省略则返回全部' } } }],
};
const DESC = VARIANT === 'old' ? OLD_DESC : NEW_DESC;

const TOOLS = [
  ['get_my_courses', '获取当前学生已选的课程列表', { type: 'object', properties: {} }],
  ['get_my_scores', '获取当前学生的成绩', { type: 'object', properties: {} }],
  ['select_course', '学生选课，添加课程到已选列表', {
    type: 'object', properties: { courseId: { type: 'integer', description: '课程ID' } }, required: ['courseId'] }],
  ['drop_course', '学生退课，从已选列表中移除课程（可通过重新选课恢复）', {
    type: 'object', properties: { courseId: { type: 'integer', description: '课程ID' } }, required: ['courseId'] }],
  ['get_course_list', DESC.get_course_list[0], DESC.get_course_list[1]],
  ['evaluate_teacher', '对某门课程的教师进行教学评价（提交后不可修改）', {
    type: 'object', properties: {
      courseId: { type: 'integer', description: '课程ID' },
      teacherId: { type: 'string', description: '教师工号' },
      score: { type: 'integer', minimum: 1, maximum: 100, description: '评分(1-100)' },
      content: { type: 'string', description: '评价内容（可选）' } },
    required: ['courseId', 'teacherId', 'score'] }],
  ['check_evaluation', '检查某门课程是否已经评价过', {
    type: 'object', properties: { courseId: { type: 'integer', description: '课程ID' } }, required: ['courseId'] }],
  ['get_my_evaluations', '获取当前学生提交的所有教学评价', { type: 'object', properties: {} }],
].map(([name, description, parameters]) => ({
  type: 'function', function: { name, description, parameters },
}));

// 问题 → 期望工具（null 表示"可以有多种合理选择"，只观察）
const CASES = [
  ['有哪些课程可以选？', 'get_course_list'],
  ['列出所有可选课程', 'get_course_list'],
  ['我想找一门叫数学的课', 'get_course_list'],
  ['我选了哪些课？', 'get_my_courses'],
  ['我的成绩怎么样？', 'get_my_scores'],
  ['帮我选课程5', 'select_course'],
  ['我要退掉课程3', 'drop_course'],
  ['我评价过哪些老师？', 'get_my_evaluations'],
];

function ask(question, tools) {
  return new Promise((resolve) => {
    const payload = JSON.stringify({
      model: 'qwen2.5:7b',
      stream: false,
      messages: [
        { role: 'system', content: SYSTEM },
        { role: 'user', content: question },
      ],
      tools,
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
          resolve({
            tool: m.tool_calls ? m.tool_calls[0].function.name : null,
            args: m.tool_calls ? m.tool_calls[0].function.arguments : null,
            text: (m.content || '').slice(0, 60),
          });
        } catch (e) { resolve({ tool: '__ERR__', args: null, text: e.message }); }
      });
    });
    req.on('error', (e) => resolve({ tool: '__ERR__', args: null, text: e.message }));
    req.write(payload);
    req.end();
  });
}

(async () => {
  console.log(`工具描述变体: ${VARIANT}   (--variant old|new 切换)\n`);
  console.log('问题'.padEnd(24) + '期望工具'.padEnd(20) + '实际选择'.padEnd(20) + '参数 / 备注');
  console.log('-'.repeat(110));
  let correct = 0, called = 0;
  for (const [q, expected] of CASES) {
    const r = await ask(q, TOOLS);
    called += r.tool ? 1 : 0;
    const ok = r.tool === expected;
    if (ok) correct++;
    const note = r.tool ? (r.args || '') : `未调工具: ${r.text}`;
    console.log(
      (q.length > 22 ? q.slice(0, 21) + '…' : q).padEnd(24)
      + expected.padEnd(20)
      + (r.tool || '(未调用)').padEnd(20)
      + (ok ? '✓ ' : '✗ ') + note);
  }
  console.log('-'.repeat(110));
  console.log(`工具选择正确: ${correct}/${CASES.length}   实际调用了工具: ${called}/${CASES.length}`);
})();
