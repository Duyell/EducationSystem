import { ref } from 'vue'
import axios from '@/utils/request'
import type { ApplyStatus, ClassTime, ClassTimeApply, FreeRoomResult } from '@/types/models'

/**
 * 管理员的排课审批数据：排课申请审批 + 课表总览（含删除）。
 *
 * 两者同属"排课"这一业务关注点，故合并到一个 composable；
 * 开课申请审批是另一个审批流，单独放在 useCourseApplyAdmin。
 */
export function useScheduleApprove() {
  // ===================== 排课申请审批 =====================
  const applies = ref<ClassTimeApply[]>([])
  const appliesLoading = ref(false)
  const appliesError = ref('')

  /** 状态筛选：空字符串 = 全部 */
  const applyStatus = ref<ApplyStatus | ''>('PENDING')

  const loadApplies = async () => {
    appliesLoading.value = true
    appliesError.value = ''
    try {
      const res = await axios.get('/api/class-time/apply', {
        params: { status: applyStatus.value || undefined },
      })
      applies.value = (res.data ?? []) as ClassTimeApply[]
    } catch (e) {
      appliesError.value = e instanceof Error ? e.message : String(e)
      applies.value = []
    } finally {
      appliesLoading.value = false
    }
  }

  /**
   * 审批通过（可指定教室）。
   *
   * ⚠️ 后端在**有冲突时硬阻断**：抛业务异常（拦截器已弹出冲突详情），不落课表。
   * 故这里用 finally 无条件刷新——无论成功与否，状态都以服务端为准，
   * 被阻断的那条会继续显示为待审批。
   *
   * 刻意**不**在这里刷新课表总览：总览面板持有自己的 composable 实例（独立状态），
   * 在这里刷新只会打到本实例里没人用的 `times` 上，白跑一次请求。
   * 总览改为在切到该标签页时自己重新拉取（见 TimetableOverviewPanel 的 active）。
   */
  const approveApply = async (id: number, roomId?: number): Promise<ClassTimeApply | null> => {
    try {
      const res = await axios.post(`/api/class-time/apply/${id}/approve`, null, {
        params: { roomId },
      })
      return (res.data ?? null) as ClassTimeApply | null
    } finally {
      await loadApplies()
    }
  }

  /** 驳回排课申请：reason 必填 */
  const rejectApply = async (id: number, reason: string): Promise<void> => {
    try {
      await axios.post(`/api/class-time/apply/${id}/reject`, { reason })
    } finally {
      await loadApplies()
    }
  }

  /** 某时段可用教室（审批改派教室时用），与冲突检测共用后端同一套重叠判据 */
  const loadFreeRooms = async (params: {
    term: string
    weekday: number
    startPeriod: number
    endPeriod: number
    startWeek: number
    endWeek: number
    minCapacity?: number
  }): Promise<FreeRoomResult | null> => {
    const res = await axios.get('/api/room/free', { params: { ...params, limit: 20 } })
    return (res.data ?? null) as FreeRoomResult | null
  }

  // ===================== 课表总览 =====================
  const times = ref<ClassTime[]>([])
  const timesLoading = ref(false)
  const timesError = ref('')

  /** 总览筛选：学期 + 星期几（都可空） */
  const term = ref('')
  const weekday = ref<number | undefined>(undefined)

  const loadTimes = async () => {
    timesLoading.value = true
    timesError.value = ''
    try {
      const res = await axios.get('/api/class-time', {
        params: {
          term: term.value || undefined,
          weekday: weekday.value,
        },
      })
      times.value = (res.data ?? []) as ClassTime[]
    } catch (e) {
      timesError.value = e instanceof Error ? e.message : String(e)
      times.value = []
    } finally {
      timesLoading.value = false
    }
  }

  /** 删除一条排课（管理员）；失败异常向上抛，由组件提示 */
  const removeTime = async (id: number): Promise<void> => {
    try {
      await axios.delete(`/api/class-time/${id}`)
    } finally {
      await loadTimes()
    }
  }

  // 刻意不做 `onMounted(两件事都加载)`：本 composable 同时服务"排课申请审批"与
  // "课表总览"两个面板，各自只该拉自己需要的数据（否则挂载一个面板会白打另一个接口）。
  // 两个面板分别在 onMounted 里调用 loadApplies / loadTimes。

  return {
    applies,
    appliesLoading,
    appliesError,
    applyStatus,
    loadApplies,
    approveApply,
    rejectApply,
    loadFreeRooms,
    times,
    timesLoading,
    timesError,
    term,
    weekday,
    loadTimes,
    removeTime,
  }
}
