import type { ScoreChangeLog } from '@/types/models'
import type { TagType } from '@/utils/schedule'

/**
 * 成绩变更日志的纯展示工具。
 *
 * 放在 utils 而不是 composables：无状态、无副作用、不依赖 Vue 响应式，只做取值与文案格式化
 * （与 `utils/exam.ts` / `utils/schedule.ts` 同一取舍）。
 *
 * 这一页要回答的核心问题是"**改前是多少、改后是多少**"（成绩申诉时唯一有用的问题），
 * 所以"哪几个字段变了、变成什么"的推导集中在这里，而不是散在模板里——
 * 模板里逐个字段写 v-if 既不好读，也没法单测。
 */

// ===================== 枚举文案 =====================

const OPERATION_LABELS: Record<string, string> = {
  INSERT: '新增',
  UPDATE: '修改',
  DELETE: '删除',
}

/** 操作类型文案（后端给的是 INSERT/UPDATE/DELETE 三个英文常量） */
export function operationLabel(operation?: string | null): string {
  if (!operation) return '—'
  return OPERATION_LABELS[operation] ?? operation
}

/** 操作类型标签色：新增=成功、修改=警告、删除=危险（删除在申诉场景里最需要被一眼看到） */
export function operationTagType(operation?: string | null): TagType {
  switch (operation) {
    case 'INSERT':
      return 'success'
    case 'UPDATE':
      return 'warning'
    case 'DELETE':
      return 'danger'
    default:
      return 'info'
  }
}

/**
 * 来源文案。
 *
 * `UI` = 界面或直接调接口改的，`AI` = 智能助手改的。**这两者必须区分**：
 * 成绩是可申诉数据，"是谁改的"直接决定找谁核实（制度依据 JW-09 §4.5）。
 */
export function sourceLabel(source?: string | null): string {
  if (source === 'AI') return 'AI 助手'
  if (source === 'UI') return '界面/接口'
  return source || '—'
}

export function sourceTagType(source?: string | null): TagType {
  return source === 'AI' ? 'primary' : 'info'
}

const ROLE_LABELS: Record<string, string> = {
  student: '学生',
  teacher: '教师',
  admin: '管理员',
}

export function roleLabel(role?: string | null): string {
  if (!role) return '—'
  return ROLE_LABELS[role] ?? role
}

// ===================== 分数与差异 =====================

/** 分数字面量：86.0 显示成 86，87.365 原样保留；空值给占位符而不是空白 */
export function formatScore(value?: number | null): string {
  if (value === undefined || value === null) return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  // toFixed(3) 再去掉无意义的尾零：成绩最多 3 位小数（绩点换算要求 87.365 这种精度）
  return String(Number(n.toFixed(3)))
}

/** 通过标记：1=通过 / 0=未通过（后端用 Integer 而不是 boolean，历史原因） */
export function formatPassed(value?: number | null): string {
  if (value === undefined || value === null) return '—'
  return value === 1 ? '通过' : '未通过'
}

/** 一个成绩字段的"改前 → 改后" */
export interface ScoreDiffPart {
  key: string
  label: string
  before: string
  after: string
  /** 该字段这次的取值是否发生变化（决定是否强调显示） */
  changed: boolean
}

/** 字段定义与取格式化值的顺序（展示顺序即此顺序：平时 → 考试 → 补考 → 总评 → 是否通过） */
const FIELD_DEFS: {
  key: string
  label: string
  before: (row: ScoreChangeLog) => number | null | undefined
  after: (row: ScoreChangeLog) => number | null | undefined
  format: (value?: number | null) => string
}[] = [
  { key: 'usual', label: '平时', before: (r) => r.beforeUsual, after: (r) => r.afterUsual, format: formatScore },
  { key: 'exam', label: '考试', before: (r) => r.beforeExam, after: (r) => r.afterExam, format: formatScore },
  { key: 'makeup', label: '补考', before: (r) => r.beforeMakeup, after: (r) => r.afterMakeup, format: formatScore },
  { key: 'total', label: '总评', before: (r) => r.beforeTotal, after: (r) => r.afterTotal, format: formatScore },
  { key: 'passed', label: '是否通过', before: (r) => r.beforePassed, after: (r) => r.afterPassed, format: formatPassed },
]

/**
 * 本次变更涉及的字段。
 *
 * @param onlyChanged `true` = 只返回真正变化的字段（UPDATE 用，避免把没动过的字段也堆在界面上）；
 *                    `false` = 返回所有有值的字段（INSERT/DELETE 用，此时界面上要展示的是"一份快照"）。
 */
export function scoreDiffParts(
  row: ScoreChangeLog,
  onlyChanged = true,
): ScoreDiffPart[] {
  return FIELD_DEFS.map((def) => {
    const before = def.format(def.before(row))
    const after = def.format(def.after(row))
    return { key: def.key, label: def.label, before, after, changed: before !== after }
  }).filter((part) => {
    if (part.changed) return true
    if (onlyChanged) return false
    // 没有变化的字段里，"两边都是空"的不展示（例如本来就没有补考成绩）
    return part.before !== '—' || part.after !== '—'
  })
}

/**
 * 一行变更的可读摘要（表格里当主文案用）。
 *
 * - UPDATE：只列变化的字段，如 `总评 86 → 50`
 * - INSERT / DELETE：给一份快照，如 `平时 80 / 考试 90 / 总评 86`
 */
export function changeSummaryText(row: ScoreChangeLog): string {
  if (row.operation === 'UPDATE') {
    const changed = scoreDiffParts(row, true)
    if (changed.length === 0) return '（字段值未变化）'
    return changed.map((p) => `${p.label} ${p.before} → ${p.after}`).join('；')
  }
  const parts = scoreDiffParts(row, false)
  const side = row.operation === 'DELETE' ? 'before' : 'after'
  if (parts.length === 0) return '（无成绩字段）'
  return parts.map((p) => `${p.label} ${p[side]}`).join(' / ')
}
