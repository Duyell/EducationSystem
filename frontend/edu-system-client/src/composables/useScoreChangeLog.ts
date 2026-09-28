import { onMounted, reactive, ref } from 'vue'
import axios from '@/utils/request'
import type { ScoreChangeLog, ScoreChangeLogQuery } from '@/types/models'

/**
 * 管理员视角的成绩变更日志：列表 + 筛选 + 服务端分页。
 *
 * 为什么单独一个 composable：页面要"查谁改了什么"这件事本质上是**取数 + 筛选 + 分页**，
 * 与渲染无关；放在视图里会让"组合面"变成第二个实现。
 *
 * 失败处理沿用本仓库既定做法：拦截器已统一弹过提示，这里只记录错误文案供页面渲染内联错误态，
 * 不重复提示（与 `useRoomManage` / `useExamManage` 一致）。
 */
export function useScoreChangeLog() {
  const list = ref<ScoreChangeLog[]>([])
  const total = ref(0)
  const loading = ref(false)
  const loadError = ref('')

  const pageNum = ref(1)
  const pageSize = ref(20)

  const query = reactive<ScoreChangeLogQuery>({
    studentId: '',
    operatorId: '',
    courseId: '',
  })

  /**
   * 空值一律转成 `undefined`（axios 会**丢掉** undefined 参数，而不是发成空串）。
   *
   * ⚠️ 这里必须小心：`courseId=` 这种空串会让 Spring 绑 Integer 直接 400，
   * 而 `studentId=` 空串虽然能过，但会在 SQL 里变成 `like '%%'` 这类无意义条件。
   */
  const search = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const courseId = Number(query.courseId)
      const res = await axios.get('/api/score/change-log', {
        params: {
          page: pageNum.value,
          pageSize: pageSize.value,
          studentId: query.studentId.trim() || undefined,
          operatorId: query.operatorId.trim() || undefined,
          courseId: query.courseId.trim() !== '' && Number.isFinite(courseId) ? courseId : undefined,
        },
      })
      const data = (res.data ?? {}) as { total?: number; list?: ScoreChangeLog[] }
      list.value = data.list ?? []
      total.value = Number(data.total ?? 0)
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : String(e)
      list.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  /** 点"查询"：从第一页开始（停在第 5 页再换条件，结果会很反直觉） */
  const submitSearch = async () => {
    pageNum.value = 1
    await search()
  }

  const resetQuery = async () => {
    query.studentId = ''
    query.operatorId = ''
    query.courseId = ''
    pageNum.value = 1
    await search()
  }

  const changePage = async (page: number) => {
    pageNum.value = page
    await search()
  }

  const changePageSize = async (size: number) => {
    pageSize.value = size
    pageNum.value = 1
    await search()
  }

  onMounted(search)

  return {
    list,
    total,
    loading,
    loadError,
    pageNum,
    pageSize,
    query,
    search,
    submitSearch,
    resetQuery,
    changePage,
    changePageSize,
  }
}
