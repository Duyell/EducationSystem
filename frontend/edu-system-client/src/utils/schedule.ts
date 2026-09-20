import type {
  ApplyStatus,
  ClassTime,
  ConflictCheck,
  ConflictItem,
  RoomType,
} from '@/types/models'

/**
 * 排课 / 教室相关的纯工具函数与选项常量。
 *
 * 放在 utils 而不是 composables：它们无状态、无副作用、不依赖 Vue 响应式，
 * 只是取值与文案格式化（工具就是工具，不要包装成 composable）。
 */

/** 一天 10 节（后端 ClassTime.MAX_PERIOD 同值） */
export const PERIOD_MIN = 1
export const PERIOD_MAX = 10

/**
 * 周次范围。
 *
 * ⚠️ 刻意不假定 1~16：用户明确说课程可能从第 9 周才开始，
 * 所以这里只给一个足够宽的上限做输入校验，不表达"学期固定多少周"。
 */
export const WEEK_MIN = 1
export const WEEK_MAX = 30

const WEEKDAY_LABELS: Record<number, string> = {
  1: '周一',
  2: '周二',
  3: '周三',
  4: '周四',
  5: '周五',
  6: '周六',
  7: '周日',
}

/** 星期几下拉选项（1..7 = 周一..周日） */
export const WEEKDAY_OPTIONS = [1, 2, 3, 4, 5, 6, 7].map((value) => ({
  value,
  label: WEEKDAY_LABELS[value] ?? `周${value}`,
}))

/** 星期几 → 中文标签；缺失时给占位符而不是空白 */
export function weekdayLabel(weekday?: number | null): string {
  if (weekday === null || weekday === undefined) return '—'
  return WEEKDAY_LABELS[weekday] ?? `周${weekday}`
}

/** "第 3-4 节"；单节显示 "第 3 节" */
export function periodText(startPeriod?: number | null, endPeriod?: number | null): string {
  if (startPeriod === null || startPeriod === undefined) return '—'
  const end = endPeriod === null || endPeriod === undefined ? startPeriod : endPeriod
  return startPeriod === end ? `第 ${startPeriod} 节` : `第 ${startPeriod}-${end} 节`
}

/** "第 1-16 周"；单周显示 "第 9 周" */
export function weekText(startWeek?: number | null, endWeek?: number | null): string {
  if (startWeek === null || startWeek === undefined) return '—'
  const end = endWeek === null || endWeek === undefined ? startWeek : endWeek
  return startWeek === end ? `第 ${startWeek} 周` : `第 ${startWeek}-${end} 周`
}

/** 时间片段的公共形状：课表、申请、冲突记录都能传进来 */
export interface SlotLike {
  weekday?: number | null
  startPeriod?: number | null
  endPeriod?: number | null
  startWeek?: number | null
  endWeek?: number | null
}

/** "周一 第 3-4 节" */
export function slotText(slot: SlotLike): string {
  return `${weekdayLabel(slot.weekday)} ${periodText(slot.startPeriod, slot.endPeriod)}`
}

/** "周一 第 3-4 节 · 第 1-16 周" —— 列表与提示里统一用它 */
export function slotWithWeekText(slot: SlotLike): string {
  return `${slotText(slot)} · ${weekText(slot.startWeek, slot.endWeek)}`
}

// ===================== 审批状态 =====================

export const APPLY_STATUS_OPTIONS: { value: ApplyStatus; label: string }[] = [
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

const APPLY_STATUS_LABELS: Record<ApplyStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

export type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

export function applyStatusLabel(status?: string | null): string {
  if (!status) return '—'
  return APPLY_STATUS_LABELS[status as ApplyStatus] ?? status
}

export function applyStatusTagType(status?: string | null): TagType {
  switch (status) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'PENDING':
      return 'warning'
    default:
      return 'info'
  }
}

// ===================== 教室 =====================

export const ROOM_TYPE_OPTIONS: { value: RoomType; label: string }[] = [
  { value: 'NORMAL', label: '普通教室' },
  { value: 'LAB', label: '实验室' },
  { value: 'MULTIMEDIA', label: '多媒体教室' },
]

const ROOM_TYPE_LABELS: Record<RoomType, string> = {
  NORMAL: '普通教室',
  LAB: '实验室',
  MULTIMEDIA: '多媒体教室',
}

export function roomTypeLabel(roomType?: string | null): string {
  if (!roomType) return '—'
  return ROOM_TYPE_LABELS[roomType as RoomType] ?? roomType
}

// ===================== 冲突 =====================

/**
 * 冲突条数 —— **必须**由两个数组的长度自行相加。
 *
 * 后端 `ConflictResult` 的 `total()` 是 record 派生方法，Jackson 只序列化 record 组件，
 * 报文里没有 `total`，直接读会恒为 undefined。
 */
export function conflictCount(result?: ConflictCheck | null): number {
  if (!result) return 0
  return (result.teacherConflicts?.length ?? 0) + (result.roomConflicts?.length ?? 0)
}

/** 一条冲突的可读描述：CS101 Java程序设计（周一 第1-2节 · 第1-16周 教1-101） */
export function conflictItemText(item: ConflictItem): string {
  const where = item.roomName ? ` ${item.roomName}` : ''
  const code = item.courseCode ?? ''
  const name = item.courseName ?? ''
  return `${code} ${name}（${slotWithWeekText(item)}${where}）`.trim()
}

/** 冲突详情字符串是否有内容（后端无冲突时写 null） */
export function hasConflictInfo(conflictInfo?: string | null): boolean {
  return !!conflictInfo && conflictInfo.trim().length > 0
}

// ===================== 课表分组 =====================

export interface TimetableGroup {
  weekday: number
  label: string
  rows: ClassTime[]
}

/** 课表按星期几分组、组内按起始节次排序（课表展示用，纯函数便于复用） */
export function groupByWeekday(times: ClassTime[]): TimetableGroup[] {
  const buckets = new Map<number, ClassTime[]>()
  for (const item of times) {
    const day = item.weekday ?? 0
    const bucket = buckets.get(day)
    if (bucket) {
      bucket.push(item)
    } else {
      buckets.set(day, [item])
    }
  }
  return [...buckets.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([weekday, rows]) => ({
      weekday,
      label: weekdayLabel(weekday),
      rows: [...rows].sort((a, b) => (a.startPeriod ?? 0) - (b.startPeriod ?? 0)),
    }))
}
