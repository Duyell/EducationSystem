import { onMounted, ref } from 'vue'
import axios from '@/utils/request'
import type { ClassTime, ClassTimeApply, CourseApply } from '@/types/models'

/**
 * 教师视角的排课数据：我的开课申请、我的排课申请、我的课表。
 *
 * 三块数据互不依赖，故各自独立加载：任意一块失败都不该让另外两块空掉
 * （例如课表接口报错时，开课申请列表仍应正常显示）。
 * 每个加载器自己吞掉异常并记录错误文案——拦截器已统一弹过提示，
 * 组件只需用 `*Error` 渲染内联错误态。
 */
export function useMyCourseApply() {
  const applies = ref<CourseApply[]>([])
  const appliesLoading = ref(false)
  const appliesError = ref('')

  const scheduleApplies = ref<ClassTimeApply[]>([])
  const scheduleLoading = ref(false)
  const scheduleError = ref('')

  const timetable = ref<ClassTime[]>([])
  const timetableLoading = ref(false)
  const timetableError = ref('')

  /** 课表学期筛选：空字符串 = 全部学期 */
  const term = ref('')

  const loadApplies = async () => {
    appliesLoading.value = true
    appliesError.value = ''
    try {
      const res = await axios.get('/api/course-apply/my')
      applies.value = (res.data ?? []) as CourseApply[]
    } catch (e) {
      appliesError.value = e instanceof Error ? e.message : String(e)
      applies.value = []
    } finally {
      appliesLoading.value = false
    }
  }

  const loadScheduleApplies = async () => {
    scheduleLoading.value = true
    scheduleError.value = ''
    try {
      const res = await axios.get('/api/class-time/apply/my')
      scheduleApplies.value = (res.data ?? []) as ClassTimeApply[]
    } catch (e) {
      scheduleError.value = e instanceof Error ? e.message : String(e)
      scheduleApplies.value = []
    } finally {
      scheduleLoading.value = false
    }
  }

  const loadTimetable = async () => {
    timetableLoading.value = true
    timetableError.value = ''
    try {
      const res = await axios.get('/api/class-time/my', {
        params: term.value ? { term: term.value } : {},
      })
      timetable.value = (res.data ?? []) as ClassTime[]
    } catch (e) {
      timetableError.value = e instanceof Error ? e.message : String(e)
      timetable.value = []
    } finally {
      timetableLoading.value = false
    }
  }

  const loadAll = async () => {
    await Promise.all([loadApplies(), loadScheduleApplies(), loadTimetable()])
  }

  onMounted(loadAll)

  return {
    applies,
    appliesLoading,
    appliesError,
    scheduleApplies,
    scheduleLoading,
    scheduleError,
    timetable,
    timetableLoading,
    timetableError,
    term,
    loadApplies,
    loadScheduleApplies,
    loadTimetable,
    loadAll,
  }
}
