import { onMounted, reactive, ref } from 'vue'
import axios from '@/utils/request'
import type { SelectionRound, SelectionRoundQuery } from '@/types/models'

/**
 * 管理员视角的选课轮次数据：列表 + 开关 + 删除。
 *
 * 只有轮次**列表**在这里管理：
 * - 新建/编辑在 `SelectionRoundForm` 自己的对话框里发请求（同 P1/P2 既有表单的做法：
 *   写入是对话框自己的职责，父级只在 `success` 后刷新列表）；
 * - **适用范围**在打开抽屉时按需加载，归 `ScopeManager` 自己管
 *   （同 P2 的 ScheduleApplyDialog：只在需要时才取自己的数据）。
 *
 * 失败处理：拦截器已统一弹过提示，这里只记录错误文案供页面渲染内联错误态，
 * 不再重复提示。写操作返回 `boolean`，让调用方决定要不要刷新列表。
 */
export function useSelectionRoundManage() {
  const rounds = ref<SelectionRound[]>([])
  const loading = ref(false)
  const loadError = ref('')

  /** 列表筛选：学期（空 = 全部）+ 状态（'' = 全部） */
  const query = reactive<SelectionRoundQuery>({
    term: '',
    status: '',
  })

  const loadRounds = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const res = await axios.get('/api/selection-round', {
        params: {
          term: query.term || undefined,
          status: query.status === '' ? undefined : query.status,
        },
      })
      rounds.value = (res.data ?? []) as SelectionRound[]
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : String(e)
      rounds.value = []
    } finally {
      loading.value = false
    }
  }

  const resetQuery = async () => {
    query.term = ''
    query.status = ''
    await loadRounds()
  }

  /** 开启 / 关闭选课（1=开启 0=关闭） */
  const setRoundStatus = async (id: number, status: number): Promise<boolean> => {
    try {
      await axios.post(`/api/selection-round/${id}/status`, { status })
      return true
    } catch {
      return false
    }
  }

  const removeRound = async (id: number): Promise<boolean> => {
    try {
      await axios.delete(`/api/selection-round/${id}`)
      return true
    } catch {
      return false
    }
  }

  onMounted(loadRounds)

  return {
    rounds,
    loading,
    loadError,
    query,
    loadRounds,
    resetQuery,
    setRoundStatus,
    removeRound,
  }
}
