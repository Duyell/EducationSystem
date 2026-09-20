// 后端实体对应的前端类型（与 com.duyell 实体及查询结果字段一致）

export interface College {
  id: number
  collegeName: string
}

export interface Major {
  id: number
  majorName: string
  /** 表单中 el-select 未选择时为 ''，故为联合类型 */
  collegeId: number | string
  collegeName?: string
}

export interface Clazz {
  id: number
  clazzName: string
  /** 表单中 el-select 未选择时为 ''，故为联合类型 */
  majorId: number | string
  grade: string
  majorName?: string
  collegeName?: string
}

export interface Teacher {
  id: number
  teacherName: string
  teacherId: string
  gender?: string
  birthday?: string
  phone?: string
  email?: string
  collegeId?: number
  title?: string
  collegeName?: string
}

export interface Student {
  id: number
  studentName: string
  studentId: string
  gender?: string
  birthday?: string
  phone?: string
  email?: string
  clazzId?: number
  collegeName?: string
  majorName?: string
  clazzName?: string
}

export interface Course {
  id: number
  courseName: string
  teacherId: string
  teacherName?: string
  /** 表单中 el-select 未选择时为 ''，故为联合类型 */
  collegeId: number | string
  collegeName?: string
  term: string
  credit: number
  classHour: number
  maxStudent: number
}

export interface Score {
  /** 新建时前端以 0 表示"尚无记录"，后端以 id 是否存在区分增改 */
  id: number
  courseId: number
  studentId: string
  studentName?: string
  courseName?: string
  term?: string
  usualScore: number
  examScore: number
  /** 由后端按 平时×0.4 + 考试×0.6 计算；未保存前前端置 0 */
  totalScore: number
}

export interface TeacherEvaluation {
  id: number
  courseId: number
  studentId: string
  teacherId: string
  score: number
  content?: string
  courseName?: string
  studentName?: string
  createTime?: string
}

/** 学生评价页：课程 + 评价状态扩展字段 */
export interface EvaluableCourse extends Course {
  evaluated: boolean
  evalScore: number
  evalContent: string
}

// ===================== P1：培养计划 / 绩点 / 学分审核 =====================
// 与后端 duyell.service 的 record 一一对应，见 docs/教务业务扩展设计.md §2、§4.3

/** 单门课程的绩点明细（GpaService.CourseGpa） */
export interface CourseGpa {
  courseCode: string
  courseName: string
  credit: number
  /** 原始总成绩 */
  rawScore: number
  /** **计入绩点所用的分数**：补考通过时为 60，否则等于原始成绩 */
  usedScore: number
  gradePoint: number
  /** 是否通过补考通过（此时按 60 分计） */
  fromMakeup: boolean
}

/** 平均学分绩点结果（GpaService.GpaResult） */
export interface GpaResult {
  gpa: number
  /** 计入 GPA 的总学分（只含已通过课程） */
  totalCredit: number
  totalGradePoint: number
  passedCount: number
  details: CourseGpa[]
}

/** 专业内排名（GpaService.RankResult）—— 只含本人名次，不含他人数据 */
export interface GpaRank {
  /** false 表示学生不存在或缺专业/年级信息 */
  ranked: boolean
  majorName?: string
  grade?: string
  rank: number
  total: number
  gpa: number
  totalCredit: number
  /** 同专业同年级最高绩点 */
  topGpa: number
  passLine: number
}

/** /gpa/my 的返回体 */
export interface GpaPayload {
  studentId: string
  term: string
  gpa: GpaResult
  rank: GpaRank
}

/** 未通过的必修课（CreditService.MissingCourse） */
export interface MissingCourse {
  courseCode: string
  courseName: string
  credit: number
  suggestSemester?: number
  /** NOT_TAKEN 未修读（要选课）/ FAILED 已修但未通过（要补考） */
  state: 'NOT_TAKEN' | 'FAILED'
}

/** 毕业学分审核结果（CreditService.AuditResult） */
export interface CreditAudit {
  /** 是否找到学生适用的培养计划 */
  planFound: boolean
  planName?: string
  creditSatisfied: boolean
  requiredSatisfied: boolean
  electiveSatisfied: boolean
  earnedCredits: number
  requiredCredits: number
  electiveCredits: number
  totalCredits: number
  earnedRequiredCredit: number
  earnedElectiveCredit: number
  missingRequired: MissingCourse[]
  unmetElectiveCodes: string[]
}
// 注意：后端 AuditResult 的 satisfied() / creditGap() 是 record 派生方法，
// Jackson 只序列化 record 组件，不会序列化它们——接口报文里没有这两个字段。
// 需要判断"是否全部达标"时，在调用方用三个 *Satisfied 标志自行推导。

/** 培养计划 */
export interface TrainingPlan {
  id?: number
  planName: string
  majorId: number | string
  /** 适用年级，决定版本 */
  grade: string
  totalCredits: number
  requiredCredits: number
  electiveCredits: number
  status: number
  remark?: string
  majorName?: string
  collegeName?: string
}

/** 培养计划课程明细 */
export interface PlanCourse {
  id?: number
  planId?: number
  courseCode: string
  courseName: string
  /** REQUIRED 必修 / ELECTIVE 选修 */
  category: 'REQUIRED' | 'ELECTIVE'
  suggestSemester?: number
  credit: number
  remark?: string
}

/** 学生定位信息（年级 + 专业，用于说明方案依据） */
export interface StudentPlacement {
  studentId: string
  studentName: string
  grade?: string
  majorId?: number
  majorName?: string
  collegeName?: string
  clazzName?: string
}

/** /training-plan/my 的返回体 */
export interface MyTrainingPlan {
  studentId: string
  placement?: StudentPlacement
  plan?: TrainingPlan
  courses: PlanCourse[]
  audit: CreditAudit
}
