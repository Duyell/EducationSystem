import type { ExamConflictCheck, ExamConflictItem, ExamSchedule, ExamType } from '@/types/models'
import type { TagType } from '@/utils/schedule'

/**
 * 考试相关的纯工具函数与选项常量。
 *
 * 放在 utils 而不是 composables：无状态、无副作用、不依赖 Vue 响应式，
 * 只做取值与文案格式化（工具就是工具，不要包装成 composable）。
 *
 * ⚠️ **这里刻意没有任何"冲突判定"逻辑。**
 * 考试的冲突判据是**半开区间**（`existing.start < newEnd AND existing.end > newStart`）：
 * 09:00-11:00 与 11:00-13:00 **不算**冲突，只是首尾相接。
 * 这与排课（P2）的节次判据**故意不同**——那边 3-4 节与 4-5 节共用第 4 节，算冲突。
 * 两套判据各自成立，**不要"统一"它们**，也不要在前端重写一遍冲突判断：
 * 冲突一律以 `POST /exam/check` 与保存时服务端的硬校验为准。
 */

// ===================== 类型 =====================

export const EXAM_TYPE_OPTIONS: { value: ExamType; label: string }[] = [
  { value: 'FINAL', label: '期末' },
  { value: 'MAKEUP', label: '补考' },
  { value: 'MIDTERM', label: '期中' },
]

const EXAM_TYPE_LABELS: Record<ExamType, string> = {
  FINAL: '期末',
  MAKEUP: '补考',
  MIDTERM: '期中',
}

/**
 * 考试类型标签。
 *
 * **优先用后端给的 `typeLabel`**（它会被序列化，是权威文案），
 * 只有它缺失时才用本地映射兜底——避免后端改了叫法而后端/前端各说一套。
 */
export function examTypeLabel(exam: Pick<ExamSchedule, 'examType' | 'typeLabel'>): string {
  if (exam.typeLabel) return exam.typeLabel
  if (!exam.examType) return '—'
  return EXAM_TYPE_LABELS[exam.examType] ?? exam.examType
}

/** 从冲突项里取类型文案（冲突项没有 examType 时给占位符） */
function conflictTypeLabel(item: ExamConflictItem): string {
  if (item.typeLabel) return item.typeLabel
  if (!item.examType) return ''
  return EXAM_TYPE_LABELS[item.examType as ExamType] ?? item.examType
}

// ===================== 时间 =====================

/**
 * 解析后端的考试时间串。
 *
 * 后端给的是**不带时区**的 ISO 本地时间（如 `2026-09-27T09:00:00`），
 * JS 对不带偏移量的日期时间串按**本地时间**解析，与后端 `LocalDateTime` 语义一致。
 */
export function parseExamTime(value?: string | null): Date | null {
  if (!value) return null
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? null : d
}

/**
 * 考试结束时间 = 开始时间 + 时长。
 *
 * ⚠️ 必须在**前端**算：后端 `ExamSchedule` 的 `endTime` 是普通 Java 方法（不是 getter），
 * Jackson 不序列化，报文里没有这个字段。
 */
export function examEndTime(exam: Pick<ExamSchedule, 'examTime' | 'durationMinutes'>): Date | null {
  const start = parseExamTime(exam.examTime)
  if (!start) return null
  const minutes = Number(exam.durationMinutes ?? 0)
  if (!Number.isFinite(minutes) || minutes < 0) return null
  return new Date(start.getTime() + minutes * 60_000)
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** "2026-09-27 09:00"；空值给占位符而不是空白 */
export function formatDateTime(value?: string | Date | null): string {
  if (!value) return '—'
  const d = value instanceof Date ? value : parseExamTime(value)
  if (!d) return typeof value === 'string' ? value : '—'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** "09:00" —— 同一天内展示起止时刻时用，比重复日期好读 */
function clockText(d: Date): string {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * "2026-09-27 09:00 ~ 11:00"。
 *
 * 只有**同一天**才省略结束端的日期；跨天（如 23:00 开始考 3 小时）就把日期写全，
 * 否则读者会以为它当天结束。
 */
export function examTimeRangeText(
  exam: Pick<ExamSchedule, 'examTime' | 'durationMinutes'>,
): string {
  const start = parseExamTime(exam.examTime)
  if (!start) return '—'
  const end = examEndTime(exam)
  if (!end) return formatDateTime(start)
  const sameDay = start.getFullYear() === end.getFullYear()
    && start.getMonth() === end.getMonth()
    && start.getDate() === end.getDate()
  return sameDay
    ? `${formatDateTime(start)} ~ ${clockText(end)}`
    : `${formatDateTime(start)} ~ ${formatDateTime(end)}`
}

/** 冲突项的起止文案（冲突项只有 examTime + durationMinutes） */
function conflictTimeText(item: ExamConflictItem): string {
  return examTimeRangeText({
    examTime: item.examTime ?? '',
    durationMinutes: Number(item.durationMinutes ?? 0),
  })
}

/** 自然日差值（先抹掉时分秒），正数表示 target 在 now 之后 */
function dayDiffOf(target: Date, now: Date): number {
  const dayStart = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  return Math.round((dayStart(target) - dayStart(now)) / 86_400_000)
}

/**
 * 相对天数："今天 / 明天 / 后天 / 3 天后 / 2 天前"。
 *
 * 按**自然日**比较（先抹掉时分秒），否则"明天 08:00"与"今天 20:00"只差 12 小时，
 * 会被算成"今天"，而学生需要的是"哪天考"。
 */
export function relativeDayText(target?: Date | null, now: Date = new Date()): string {
  if (!target) return ''
  const days = dayDiffOf(target, now)
  if (days === 0) return '今天'
  if (days === 1) return '明天'
  if (days === 2) return '后天'
  if (days > 0) return `${days} 天后`
  if (days === -1) return '昨天'
  return `${Math.abs(days)} 天前`
}

/**
 * target 是否落在**今天起 days 个自然日内**（含今天）。
 *
 * 用于"临近考试"的强调色：只往未来方向判，已经过去的不算临近。
 * 与 `relativeDayText` 共用同一套自然日口径，避免"显示 3 天后却不认为临近"这种自相矛盾。
 */
export function isWithinDays(target?: Date | null, now: Date = new Date(), days = 3): boolean {
  if (!target) return false
  const diff = dayDiffOf(target, now)
  return diff >= 0 && diff <= days
}

// ===================== 待考 / 进行中 / 已考 =====================

export type ExamPhase = 'UPCOMING' | 'ONGOING' | 'PAST'

export interface ExamPhaseInfo {
  phase: ExamPhase
  label: string
  tagType: TagType
  hint: string
}

/**
 * 按当前时间判断这场考试处于哪个阶段。
 *
 * 用**半开区间** `[start, end)`：正好到开始时刻算"进行中"，正好到结束时刻算"已考"。
 * 这只是"现在处于哪个阶段"的展示判断，**不是冲突判断**——
 * 冲突仍然只由服务端按 `existing.start < newEnd AND existing.end > newStart` 判定。
 */
export function examPhaseOf(
  exam: Pick<ExamSchedule, 'examTime' | 'durationMinutes'>,
  now: Date = new Date(),
): ExamPhaseInfo {
  const start = parseExamTime(exam.examTime)
  const end = examEndTime(exam)
  if (!start || !end) {
    return {
      phase: 'UPCOMING',
      label: '时间待定',
      tagType: 'info',
      hint: '这场考试还没有有效的开始时间或时长。',
    }
  }
  if (now.getTime() < start.getTime()) {
    return {
      phase: 'UPCOMING',
      label: '待考',
      tagType: 'primary',
      hint: `尚未开始：${formatDateTime(start)} 开考。`,
    }
  }
  if (now.getTime() < end.getTime()) {
    return {
      phase: 'ONGOING',
      label: '进行中',
      tagType: 'success',
      hint: `正在进行：${clockText(start)} 开始，${clockText(end)} 结束。`,
    }
  }
  return {
    phase: 'PAST',
    label: '已考',
    tagType: 'info',
    hint: `已结束：${formatDateTime(end)} 结束。`,
  }
}

/** 这场考试是否尚未结束（含进行中）—— 学生页据此分成"待考/已考"两段 */
export function isExamNotFinished(
  exam: Pick<ExamSchedule, 'examTime' | 'durationMinutes'>,
  now: Date = new Date(),
): boolean {
  const end = examEndTime(exam)
  if (!end) return true // 时间有问题的排在"待考"里，免得它凭空消失
  return now.getTime() < end.getTime()
}

/** 一场考试是否有效（status 1=有效 0=作废）；字段缺失按有效处理 */
export function isExamActive(exam: Pick<ExamSchedule, 'status'>): boolean {
  return exam.status === undefined || exam.status === null || exam.status === 1
}

/** 状态文案与标签色（管理端表格用） */
export function examStatusLabel(status?: number | null): string {
  if (status === 0) return '作废'
  return '有效'
}

export function examStatusTagType(status?: number | null): TagType {
  return status === 0 ? 'info' : 'success'
}

// ===================== 冲突 =====================

/**
 * 冲突条数 —— **必须**由两个数组的长度自行相加。
 *
 * 报文里只有 `conflict` / `roomConflicts` / `studentConflicts` 三个字段，没有"共几条"。
 * 也不要指望后端补一个：那种派生方法在 record 上**不会被 Jackson 序列化**
 * （P1 的 `AuditResult.satisfied()`、P3 的 `SelectionStatus.readOnly()` 都栽在这上面），
 * 所以计数这种事就放在调用方做。
 */
export function examConflictCount(result?: ExamConflictCheck | null): number {
  if (!result) return 0
  return (result.roomConflicts?.length ?? 0) + (result.studentConflicts?.length ?? 0)
}

/**
 * 一条考试冲突的可读描述。
 *
 * ⚠️ 只用契约里保证存在的字段（courseCode / courseName / examType / examTime /
 * durationMinutes / roomName），不依赖可能不存在的 id 之类，避免"渲染出 undefined"。
 */
export function examConflictItemText(item: ExamConflictItem): string {
  const head = `${item.courseCode ?? ''} ${item.courseName ?? ''}`.trim()
  const type = conflictTypeLabel(item)
  const at = conflictTimeText(item)
  const room = item.roomName ? ` ${item.roomName}` : ''
  const detail = [type, at].filter((s) => s && s !== '—').join(' ')
  const suffix = `${detail}${room}`.trim()
  if (!head) return suffix || '—'
  return suffix ? `${head}（${suffix}）` : head
}
