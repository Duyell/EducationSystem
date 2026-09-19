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
