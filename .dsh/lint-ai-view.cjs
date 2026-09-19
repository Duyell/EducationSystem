/**
 * 用 fe-ui-ts-linter 审核前端文件（项目级补充脚本）
 *
 * 用法：node .dsh/lint-ai-view.cjs [目标文件]
 *   默认目标：frontend/edu-system-client/src/views/ai/index.vue
 *
 * 为什么要这个脚本：fe-ui-ts-linter 的内置规则集是为 React 设计的，对 Vue 有两个缺口
 * （不识别 v-for 的 key、不检查 ref 声明方式）。本脚本补上这两条 Vue 规则，
 * 并读取 .dsh/feui-lint.json 作为规则取舍来源。
 */
const fs = require('fs');
const path = require('path');
const { FeUILinter, defaultRules } = require('C:/Users/53473/.dsh/skills/fe-ui-ts-linter/scripts/linter.js');

const REPO_ROOT = path.resolve(__dirname, '..');
const TARGET = path.resolve(
  process.argv[2] || path.join(REPO_ROOT, 'frontend/edu-system-client/src/views/ai/index.vue'),
);
const code = fs.readFileSync(TARGET, 'utf8');

const linter = new FeUILinter({
  rules: defaultRules,
  // 规则取舍集中在 .dsh/feui-lint.json，便于复用与评审
  userConfig: JSON.parse(
    fs.readFileSync(path.join(__dirname, 'feui-lint.json'), 'utf8'),
  ).rules,
});

// Vue 专属补充：v-for 必须有 key（内置 ui/require-key 只检测 .map()）
linter.addPatternRule(
  'vue/v-for-require-key',
  '<[^>]*v-for=(?![^>]*:key)',
  'error',
  'v-for 缺少 :key',
);

// Vue 专属补充：ref() 结果不应被重新赋值（应为 const）
// 实测修正了两次才命中：
//   1) 最初误判为"空格对齐"问题；
//   2) 真因是泛型——实际代码为 ref<ChatMsg | null>(null)，'ref\(' 无法匹配 'ref<...>('。
// 故模式需同时接受 ref( 与 ref<...>(。
linter.addPatternRule(
  'vue/prefer-const-ref',
  '^\\s*let\\s+\\w+\\s*=\\s*ref\\s*(?:<[^>]*>)?\\s*\\(',
  'warn',
  'ref() 声明为 let，若未整体重新赋值应改为 const',
);

const findings = linter.lint(code, { filename: path.basename(TARGET) });
const summary = linter.summarize(findings);

console.log(`# 代码审核报告\n`);
console.log(`文件：${TARGET}`);
console.log(`总行数：${code.split('\n').length}`);
console.log(`问题总数：${summary.total}（error: ${summary.error}, warn: ${summary.warn}, info: ${summary.info}）\n`);

const label = { error: '🔴 Error（必须修复）', warn: '🟡 Warn（建议修复）', info: '🔵 Info（提示）' };
for (const sev of ['error', 'warn', 'info']) {
  const items = summary.buckets[sev];
  console.log(`## ${label[sev]} —— ${items.length} 条`);
  if (!items.length) {
    console.log('（无）\n');
    continue;
  }
  // 按规则聚合，避免同类规则刷屏
  const byRule = {};
  for (const f of items) (byRule[f.ruleId] ||= []).push(f);
  for (const [ruleId, list] of Object.entries(byRule)) {
    console.log(`- [${ruleId}] ${list.length} 处  —— ${list[0].message.split('：')[0]}`);
    for (const f of list.slice(0, 12)) {
      console.log(`    L${f.line}: ${f.message.slice(0, 150)}`);
    }
    if (list.length > 12) console.log(`    ...另有 ${list.length - 12} 处`);
  }
  console.log('');
}
