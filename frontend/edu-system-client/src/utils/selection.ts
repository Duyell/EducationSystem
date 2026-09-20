import type { Course, SelectionRound, SelectionRoundScope } from '@/types/models'
import type { TagType } from '@/utils/schedule'

/**
 * 选课轮次 / 选课的纯工具函数与选项常量。
 *
 * 放在 utils 而不是 composables：无状态、无副作用、不依赖 Vue 响应式，
 * 只做取值与文案格式化（工具就是工具，不要包装成 composable）。
 *
 * `TagType` 从 utils/schedule.ts 复用：它是 Element Plus 标签色的公共联合类型，
 * 再声明一份就会出现两个"同义不同源"的类型。
 */

// ===================== 时间 =====================

/** ISO 本地时间串 → "YYYY-MM-DD HH:mm"；空值给占位符而不是空白 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** "起 ~ 止"，两端都空时给占位符 */
export function dateTimeRangeText(start?: string | null, end?: string | null): string {
  if (!start && !end) return '—'
  return `${formatDateTime(start)} ~ ${formatDateTime(end)}`
}

/**
 * 当前时间是否落在 [start, end] 内（闭区间）。
 *
 * ⚠️ 用 `new Date('2030-01-01T08:00:00')` 而不是拼时区：ISO 日期时间串**不带偏移量**时，
 * JS 按**本地时间**解析，与后端 `LocalDateTime` 的语义一致。
 * 任一端缺失都返回 false —— 时间窗不完整就不该被当成"开放中"。
 */
function containsNow(start?: string | null, end?: string | null, now: Date = new Date()): boolean {
  if (!start || !end) return false
  const s = new Date(start)
  const e = new Date(end)
  if (Number.isNaN(s.getTime()) || Number.isNaN(e.getTime())) return false
  return now.getTime() >= s.getTime() && now.getTime() <= e.getTime()
}

// ===================== 轮次状态 =====================

/**
 * 轮次的四种状态。
 *
 * 用户要求明确区分「可选 / 只能退 / 只能看」；这里多出一个 `IDLE`：
 * 开关开着但当前不在任何时间窗内（比如窗还没开始、或已经结束）。
 * 不把这种情况硬塞进"可选"或"只能退"里，否则页面会骗人。
 */
export type RoundPhase = 'SELECTING' | 'DROPPING' | 'IDLE' | 'CLOSED'

export interface RoundPhaseInfo {
  phase: RoundPhase
  /** 表格里显示的短标签 */
  label: string
  tagType: TagType
  /** 悬浮提示：说清"为什么是这个状态、此时学生能做什么" */
  hint: string
}

/**
 * 由「开关 + 两个时间窗」推导轮次状态。
 *
 * 后端把三态判定放在学生侧的 `SelectionStatus` 里返回，管理员侧没有对应字段，
 * 所以这里**必须前端算**，并把这一点写进页面提示，避免管理员以为数字是后端给的。
 */
export function roundPhaseOf(round: SelectionRound, now: Date = new Date()): RoundPhaseInfo {
  const inSelect = containsNow(round.selectStart, round.selectEnd, now)
  const inDrop = containsNow(round.dropStart, round.dropEnd, now)

  if (round.status !== 1) {
    return {
      phase: 'CLOSED',
      label: '已关闭·只能看',
      tagType: 'info',
      hint: '管理员尚未开启（或已关闭）本轮选课：学生只能浏览课程，不能选也不能退。',
    }
  }
  if (inSelect) {
    return {
      phase: 'SELECTING',
      label: '开启中·可选',
      tagType: 'success',
      hint: '在选课开放时间内：学生可以选课，选课期间也可以退课。',
    }
  }
  if (inDrop) {
    return {
      phase: 'DROPPING',
      label: '补退选·只能退',
      tagType: 'warning',
      hint: '选课窗口已结束、补退选窗口开放：学生只能退课，不能再选课。',
    }
  }
  return {
    phase: 'IDLE',
    label: '已开启·不在时间窗',
    tagType: 'warning',
    hint: '开关是开启的，但当前时间既不选课窗也不在补退选窗内，学生暂时不能选也不能退。',
  }
}

/**
 * 轮次状态筛选下拉。
 *
 * ⚠️ 刻意**不放**「全部状态」这一项：它的值只能写成空串，而 el-select 把空串当作
 * "未选择"，选中后显示的是 placeholder 而不是"全部状态"，反而像坏了。
 * 用 `clearable` + placeholder「状态」，清空即不筛，语义和显示都正确。
 */
export const ROUND_STATUS_OPTIONS: { value: number; label: string }[] = [
  { value: 1, label: '开启' },
  { value: 0, label: '关闭' },
]

/** 开关是否开启（1=开启） */
export function isRoundOpen(round: SelectionRound): boolean {
  return round.status === 1
}

// ===================== 适用范围 =====================

/** 一条范围的可读文案："2023 级 / 计算机科学与技术 / 计算机学院" */
export function scopeText(scope: SelectionRoundScope): string {
  const parts: string[] = []
  if (scope.grade) parts.push(`${scope.grade} 级`)
  if (scope.majorId !== null && scope.majorId !== undefined) {
    parts.push(scope.majorName || `专业 #${scope.majorId}`)
  }
  if (scope.collegeId !== null && scope.collegeId !== undefined) {
    parts.push(scope.collegeName || `学院 #${scope.collegeId}`)
  }
  return parts.length > 0 ? parts.join(' / ') : '不限'
}

/**
 * 范围摘要（表格列用）。
 *
 * **一条范围都没有 = 不限**：这正是后端拒绝"全空范围记录"的原因——
 * "不限"用"没有范围行"表达，而不是用一行全空的记录表达。
 */
export function scopesSummary(scopes?: SelectionRoundScope[] | null): string {
  if (!scopes || scopes.length === 0) return '不限'
  return scopes.map(scopeText).join('；')
}

// ===================== 学分 =====================

/**
 * 已选课程总学分。
 *
 * 学分是 decimal(4,1)，累加会出现 14.499999… 这类浮点噪声，
 * 故统一按 1 位小数收敛后再返回（与后端 decimal(5,1) 的口径一致）。
 */
export function totalCredits(courses: Course[]): number {
  const sum = courses.reduce((acc, c) => acc + Number(c.credit ?? 0), 0)
  return Math.round(sum * 10) / 10
}

/** 学分是否已超出本轮上限（无上限时永远 false） */
export function isOverCreditCap(total: number, maxCredits?: number | null): boolean {
  if (maxCredits === null || maxCredits === undefined) return false
  return total > Number(maxCredits)
}
