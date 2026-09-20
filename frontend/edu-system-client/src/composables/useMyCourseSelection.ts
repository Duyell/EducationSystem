import { computed, onMounted, ref } from 'vue'
import axios from '@/utils/request'
import type { Course, SelectableCourse, SelectionStatus } from '@/types/models'
import { isOverCreditCap, totalCredits } from '@/utils/selection'

/**
 * 一个学期的默认值。
 *
 * 取 P3 迁移脚本种子中**已开启**那一轮的学期（该学期课程也最多），
 * 这样页面一进来就是有数据、可操作的状态；学生随时可以改成别的学期。
 */
const DEFAULT_TERM = '2024-2025-1'

/**
 * 学生视角的选课数据：当前选课状态、可选课程、我的已选。
 *
 * 三块数据各自独立加载：任一块失败都不该让另外两块空掉。
 * 失败时只记录错误文案（拦截器已统一提示过），页面用 `*Error` 渲染内联错误态。
 *
 * ⚠️ 「能不能选 / 能不能退」**以服务端为准**（`SelectionStatus.canSelect` / `canDrop`）：
 * 两端的判定都要看开关 + 时间窗 + 适用范围 + 学分上限，前端再算一遍必然走偏。
 * 前端只负责把服务端给的 `reason` 显示出来。
 */
export function useMyCourseSelection() {
  const term = ref(DEFAULT_TERM)

  const status = ref<SelectionStatus | null>(null)
  const statusLoading = ref(false)
  const statusError = ref('')

  const selectable = ref<SelectableCourse[]>([])
  const listLoading = ref(false)
  const listError = ref('')

  const myCourses = ref<Course[]>([])
  const myLoading = ref(false)
  const myError = ref('')

  /** 已选学分合计（1 位小数收敛，见 utils/selection.ts） */
  const myTotalCredits = computed(() => totalCredits(myCourses.value))
  /** 本轮学分上限，可空 = 不限 */
  const maxCredits = computed(() => status.value?.maxCredits ?? null)
  const overCreditCap = computed(() => isOverCreditCap(myTotalCredits.value, maxCredits.value))

  const loadStatus = async () => {
    if (!term.value) {
      status.value = null
      return
    }
    statusLoading.value = true
    statusError.value = ''
    try {
      const res = await axios.get('/api/selection-round/current', { params: { term: term.value } })
      status.value = (res.data ?? null) as SelectionStatus | null
    } catch (e) {
      statusError.value = e instanceof Error ? e.message : String(e)
      status.value = null
    } finally {
      statusLoading.value = false
    }
  }

  const loadSelectable = async () => {
    if (!term.value) {
      selectable.value = []
      return
    }
    listLoading.value = true
    listError.value = ''
    try {
      const res = await axios.get('/api/course-selection/selectable', { params: { term: term.value } })
      selectable.value = (res.data ?? []) as SelectableCourse[]
    } catch (e) {
      listError.value = e instanceof Error ? e.message : String(e)
      selectable.value = []
    } finally {
      listLoading.value = false
    }
  }

  const loadMyCourses = async () => {
    myLoading.value = true
    myError.value = ''
    try {
      const res = await axios.get('/api/course-selection/my')
      myCourses.value = (res.data ?? []) as Course[]
    } catch (e) {
      myError.value = e instanceof Error ? e.message : String(e)
      myCourses.value = []
    } finally {
      myLoading.value = false
    }
  }

  const loadAll = async () => {
    await Promise.all([loadStatus(), loadSelectable(), loadMyCourses()])
  }

  /**
   * 选课。成功返回 true。
   *
   * 成功后必须**同时刷新可选列表与已选列表**：同一个接口的 `selected` 标记会变，
   * 学分合计也在已选那边，只刷一个会让两处自相矛盾。
   */
  const selectCourse = async (courseId: number): Promise<boolean> => {
    try {
      await axios.post(`/api/course-selection/select/${courseId}`)
      await Promise.all([loadSelectable(), loadMyCourses()])
      return true
    } catch {
      return false
    }
  }

  /** 退课。只有选课期内或补退选期内才允许，否则后端返回业务错误说明原因 */
  const dropCourse = async (courseId: number): Promise<boolean> => {
    try {
      await axios.delete(`/api/course-selection/${courseId}`)
      await Promise.all([loadSelectable(), loadMyCourses()])
      return true
    } catch {
      return false
    }
  }

  onMounted(loadAll)

  return {
    term,
    status,
    statusLoading,
    statusError,
    selectable,
    listLoading,
    listError,
    myCourses,
    myLoading,
    myError,
    myTotalCredits,
    maxCredits,
    overCreditCap,
    loadStatus,
    loadSelectable,
    loadMyCourses,
    loadAll,
    selectCourse,
    dropCourse,
  }
}
