/**
 * 跨平台导入大小写审计（一次性排查脚本，不是验证套件的一部分）。
 *
 * 起因：CI 上 `vue-tsc --build` 报
 *   src/router/index.ts(20,33): error TS2307: Cannot find module '../views/index.vue'
 * ——Windows 文件系统大小写不敏感，`Index.vue` 用小写导入照样能解析；Linux 不行。
 * 也就是说**前端在 Linux 上根本构建不起来**（docker-compose 里那条前端路径同理），
 * 只是以前没人跑过。
 *
 * 本脚本把 `git ls-files` 的清单（大小写真实）与源码里的相对导入逐个比对，
 * 找出"靠 Windows 大小写不敏感才活着"的导入。
 *
 * 用法：
 *   git ls-files frontend/edu-system-client/src > .dsh/tracked.txt
 *   node .dsh/audit-import-case.cjs
 */
const fs = require('node:fs')
const path = require('node:path')

const SRC = path.join(__dirname, '..', 'frontend', 'edu-system-client', 'src')
const TRACKED = path.join(__dirname, 'tracked.txt')

const tracked = new Set(
  fs
    .readFileSync(TRACKED, 'utf8')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean),
)

function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...walk(full))
    else if (/\.(ts|vue|js)$/.test(entry.name)) out.push(full)
  }
  return out
}

/** 把 from './x' 解析成仓库相对路径的候选（含 .ts/.vue/index.ts 补全） */
function candidates(repoRelDir, spec) {
  // 样式/图片等**带扩展名的资源**由打包器解析，不在"模块大小写"的讨论范围内：
  // 它们不参与 TS 的模块解析，Linux 上也不会因为大小写而失败（文件系统只需真实存在）
  if (/\.(css|scss|sass|less|png|jpe?g|svg|gif|webp|ico|json|woff2?|ttf)$/i.test(spec)) {
    return []
  }
  const parts = repoRelDir.split('/').filter(Boolean)
  for (const seg of spec.split('/')) {
    if (seg === '..') parts.pop()
    else if (seg !== '.' && seg !== '') parts.push(seg)
  }
  const base = parts.join('/')
  if (/\.(vue|ts|js)$/.test(spec)) return [base]
  return [base + '.ts', base + '.vue', base + '/index.ts', base + '/index.vue']
}

const problems = []
for (const file of walk(SRC)) {
  const repoRel = path.relative(path.join(__dirname, '..'), file).split(path.sep).join('/')
  const repoRelDir = repoRel.split('/').slice(0, -1).join('/')
  const text = fs.readFileSync(file, 'utf8')
  const re = /(?:from|import)\s*\(?\s*'(\.[^']+)'/g
  let m
  while ((m = re.exec(text)) !== null) {
    const spec = m[1]
    const cands = candidates(repoRelDir, spec)
    // 空候选 = 这类导入不参与本次审计（样式/资源），跳过
    if (cands.length === 0) continue
    if (!cands.some((c) => tracked.has(c))) {
      problems.push({ file: repoRel, spec, tried: cands.join(' | ') })
    }
  }
}

if (problems.length === 0) {
  console.log('OK: 没有大小写不匹配的相对导入（' + tracked.size + ' 个受控文件参与比对）')
  process.exit(0)
}
console.log('发现 ' + problems.length + ' 处可疑导入：')
for (const p of problems) {
  console.log('  ' + p.file + "  ->  '" + p.spec + "'")
  console.log('      试过: ' + p.tried)
}
process.exit(1)
