import { onMounted, ref } from 'vue'
import axios from '@/utils/request'
import type { ApplyStatus, CourseApply } from '@/types/models'

/**
 * 管理员的开课申请审批数据。
 *
 * 单个审批流 = 单个 composable（按业务关注点拆分，不做一个"管理员大而全"的 composable）。
 * 审批动作放在这里而不是组件里：它需要"调用接口 + 刷新列表"这一个完整副作用，
 * 组件只负责确认交互与提示文案。
 */
export function useCourseApplyAdmin() {
  const list = ref<CourseApply[]>([])
  const loading = ref(false)
  const loadError = ref('')

  /** 审批状态筛选：空字符串 = 全部（后端 status 为空时不加条件） */
  const status = ref<ApplyStatus | ''>('PENDING')
  const term = ref('')

  const load = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const res = await axios.get('/api/course-apply', {
        params: {
          status: status.value || undefined,
          term: term.value || undefined,
        },
      })
      list.value = (res.data ?? []) as CourseApply[]
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : String(e)
      list.value = []
    } finally {
      loading.value = false
    }
  }

  /**
   * 审批通过：后端据申请生成 course 行。
   * 返回生成结果（含 `createdCourseId`）供页面提示；失败时异常向上抛，由组件决定是否关闭弹窗。
   */
  const approve = async (id: number): Promise<CourseApply | null> => {
    try {
      const res = await axios.post(`/api/course-apply/${id}/approve`)
      return (res.data ?? null) as CourseApply | null
    } finally {
      await load()
    }
  }

  /** 驳回：reason 必填（后端校验非空），空理由会被后端拒绝 */
  const reject = async (id: number, reason: string): Promise<void> => {
    try {
      await axios.post(`/api/course-apply/${id}/reject`, { reason })
    } finally {
      await load()
    }
  }

  onMounted(load)

  return { list, loading, loadError, status, term, load, approve, reject }
}
