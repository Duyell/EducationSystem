import { computed, onMounted, ref } from 'vue'
import axios from '@/utils/request'
import {
  examEndTime,
  examPhaseOf,
  examTypeLabel,
  formatDateTime,
  isExamNotFinished,
  isWithinDays,
  parseExamTime,
  relativeDayText,
} from '@/utils/exam'
import type { ExamSchedule } from '@/types/models'

/** "我的考试"页顶部的概览数据 */
export interface MyExamSummaryData {
  total: number
  /** 尚未结束的场次（含正在进行） */
  pendingCount: number
  /** 最近一场（尚未结束里最早的） */
  next?: ExamSchedule
  /** 最近一场是否临近（正在进行，或 3 天内开考）——概览强调色用 */
  urgent: boolean
  /** 可直接渲染的一整句，派生逻辑不放进模板 */
  text: string
}

/**
 * 学生视角的考试数据：我的考试 + 学期/未开考筛选 + 待考与已考的分段 + 概览。
 *
 * 两个筛选条件都跟服务端走（`upcoming=true` 只返回**尚未开考**的），
 * 而"待考 / 已考"的分段是**前端**按当前时间算的：
 * 报文中没有 `upcoming` 字段（那是普通 Java 方法，Jackson 不序列化）。
 *
 * `now` 是**源状态**而不是在 computed 里现读时钟：
 * computed 保持纯函数、只在数据或筛选变化时重算，行为可预期；
 * 每次 `load()` 会刷新它（另有「刷新」按钮，学生想更新"还有几天"时点一下即可）。
 */
export function useMyExams() {
  const exams = ref<ExamSchedule[]>([])
  const loading = ref(false)
  const loadError = ref('')

  /** 学期筛选（空 = 全部学期） */
  const term = ref('')
  /** 只看未开考（映射到服务端 upcoming=true） */
  const upcomingOnly = ref(false)

  /** 判定"待考/已考"的基准时刻；每次加载刷新，避免页面放久了状态失真 */
  const now = ref(new Date())

  const load = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const res = await axios.get('/api/exam/my', {
        params: {
          term: term.value || undefined,
          upcoming: upcomingOnly.value ? true : undefined,
        },
      })
      exams.value = (res.data ?? []) as ExamSchedule[]
      now.value = new Date()
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : String(e)
      exams.value = []
    } finally {
      loading.value = false
    }
  }

  const resetFilters = async () => {
    term.value = ''
    upcomingOnly.value = false
    await load()
  }

  /** 排序键：无效时间排到末尾，避免它们插在最前面 */
  const timeValue = (exam: ExamSchedule): number => {
    const d = parseExamTime(exam.examTime)
    return d ? d.getTime() : Number.MAX_SAFE_INTEGER
  }

  /** 待考（含正在进行），按开考时间升序 —— "我接下来有什么考试" */
  const pendingExams = computed(() =>
    exams.value
      .filter((e) => isExamNotFinished(e, now.value))
      .sort((a, b) => timeValue(a) - timeValue(b)),
  )

  /** 已考，按开考时间**降序** —— 刚考完的排最上面 */
  const pastExams = computed(() =>
    exams.value
      .filter((e) => !isExamNotFinished(e, now.value))
      .sort((a, b) => timeValue(b) - timeValue(a)),
  )

  const summary = computed<MyExamSummaryData>(() => {
    const total = exams.value.length
    if (total === 0) {
      return { total: 0, pendingCount: 0, next: undefined, urgent: false, text: '暂无考试安排' }
    }
    const pending = pendingExams.value
    // 用"取第一个并判空"代替 length 判断：tsconfig 开了 noUncheckedIndexedAccess，
    // 索引访问天然是 `T | undefined`，这样写既拿到类型收窄又不必用非空断言。
    const next = pending[0]
    if (!next) {
      return {
        total,
        pendingCount: 0,
        next: undefined,
        urgent: false,
        text: `共 ${total} 场考试，均已结束`,
      }
    }
    const phase = examPhaseOf(next, now.value)
    const start = parseExamTime(next.examTime)
    const when = phase.phase === 'ONGOING'
      ? '正在进行'
      : `${relativeDayText(start, now.value)} ${formatDateTime(start)}`
    const where = next.roomName ?? '考场待定'
    const title = next.courseName ?? next.courseCode ?? '待考课程'
    return {
      total,
      pendingCount: pending.length,
      next,
      // 正在进行，或 3 天内开考 —— 概览用强调色，让"最近有考试"一眼可见
      urgent: phase.phase === 'ONGOING' || isWithinDays(start, now.value, 3),
      text: `共 ${total} 场考试，待考 ${pending.length} 场。最近一场：${when} ${title}`
        + `（${examTypeLabel(next)} · ${where}）`,
    }
  })

  /** 最近一场的结束时间文案（概览副标题用），无待考时为占位符 */
  const nextEndText = computed(() => {
    const next = summary.value.next
    if (!next) return ''
    const end = examEndTime(next)
    return end ? `${formatDateTime(end)} 结束` : ''
  })

  onMounted(load)

  return {
    exams,
    loading,
    loadError,
    term,
    upcomingOnly,
    now,
    pendingExams,
    pastExams,
    summary,
    nextEndText,
    load,
    resetFilters,
  }
}
