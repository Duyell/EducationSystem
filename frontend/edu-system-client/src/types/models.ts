// 后端实体对应的前端类型（与 com.duyell 实体及查询结果字段一致）

export interface College {
  id: number
  collegeName: string
}

export interface Major {
  id: number
  majorName: string
  collegeId: number
  collegeName?: string
}

export interface Clazz {
  id: number
  clazzName: string
  majorId: number
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
  collegeId: number
  collegeName?: string
  term: string
  credit: number
  classHour: number
  maxStudent: number
}

export interface Score {
  id?: number
  courseId: number
  studentId: string
  studentName?: string
  courseName?: string
  term?: string
  usualScore: number
  examScore: number
  totalScore?: number
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
