import { onMounted, reactive, ref } from 'vue'
import axios from '@/utils/request'
import type { Course, ExamQuery, ExamSchedule, Room } from '@/types/models'

/**
 * 管理员视角的考试数据：列表 + 筛选 + 删除 + 下拉选项。
 *
 * 只有**列表**在这里管理：新建/编辑在 `ExamForm` 自己的对话框里发请求
 * （同 P1/P2/P3 既有表单的做法：写入是对话框自己的职责，父级只在 `success` 后刷新列表）。
 *
 * 下拉选项（课程、教室）也放这里：它们是页面级的公共数据，对话框只是接收方，
 * 让对话框自己去取会变成每次开窗都请求一遍。
 *
 * 失败处理：拦截器已统一弹过提示，这里只记录错误文案供页面渲染内联错误态，
 * 不再重复提示。写操作返回 `boolean`，让调用方决定要不要刷新列表。
 */
export function useExamManage() {
  const exams = ref<ExamSchedule[]>([])
  const loading = ref(false)
  const loadError = ref('')

  /** 列表筛选：学期/类型/课程（空 = 不筛） */
  const query = reactive<ExamQuery>({
    term: '',
    examType: '',
    courseId: undefined,
  })

  const courseOptions = ref<Course[]>([])
  const roomOptions = ref<Room[]>([])

  const loadExams = async () => {
    loading.value = true
    loadError.value = ''
    try {
      const res = await axios.get('/api/exam', {
        params: {
          term: query.term || undefined,
          examType: query.examType || undefined,
          // ⚠️ 用 `|| undefined` 而不是 `?? undefined`：清空 el-select 时它可能给出空串，
          // 而 axios 会把空串发成 `courseId=`，Spring 绑 Integer 会直接 400。
          // 数字 id 不会是 0，所以 `||` 在这里是安全的。
          courseId: query.courseId || undefined,
        },
      })
      exams.value = (res.data ?? []) as ExamSchedule[]
    } catch (e) {
      loadError.value = e instanceof Error ? e.message : String(e)
      exams.value = []
    } finally {
      loading.value = false
    }
  }

  const resetQuery = async () => {
    query.term = ''
    query.examType = ''
    query.courseId = undefined
    await loadExams()
  }

  const removeExam = async (id: number): Promise<boolean> => {
    try {
      await axios.delete(`/api/exam/${id}`)
      return true
    } catch {
      return false
    }
  }

  /**
   * 课程下拉。
   *
   * ⚠️ 分页参数是 `page`（课程接口当年用的是这个名字），
   * 而教室接口用的是 `pageNum` —— 两者不通用，写错会静默只拿到第一页。
   */
  const loadCourseOptions = async () => {
    try {
      const res = await axios.get('/api/course', { params: { page: 1, pageSize: 200 } })
      courseOptions.value = (res.data?.list ?? []) as Course[]
    } catch {
      courseOptions.value = []
    }
  }

  /** 教室下拉（参数名是 `pageNum`，见上） */
  const loadRoomOptions = async () => {
    try {
      const res = await axios.get('/api/room', { params: { pageNum: 1, pageSize: 200 } })
      roomOptions.value = (res.data?.list ?? []) as Room[]
    } catch {
      roomOptions.value = []
    }
  }

  onMounted(() => {
    loadExams()
    loadCourseOptions()
    loadRoomOptions()
  })

  return {
    exams,
    loading,
    loadError,
    query,
    courseOptions,
    roomOptions,
    loadExams,
    resetQuery,
    removeExam,
  }
}
