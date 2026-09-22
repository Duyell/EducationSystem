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
  /**
   * 课程代码，如 CS101。
   *
   * 同一门课的所有开课行共用同一代码，是培养计划关联、已修判定、补考关联的唯一依据
   * （见 docs/教务业务扩展设计.md §3.1）。由管理员在开课时填写。
   */
  courseCode?: string
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

// ===================== P2：排课 / 教室 / 冲突检测 =====================
// 与后端 com.duyell 实体、duyell.service.ScheduleService 的 record 一一对应，
// 见 docs/教务业务扩展设计.md §2.4、§3.2(4)(5)(6)(10)

/** 审批状态（开课申请与排课申请共用一套状态值） */
export type ApplyStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 教室类型 */
export type RoomType = 'NORMAL' | 'LAB' | 'MULTIMEDIA'

/** 后端分页封装 utils.PageResult<T> */
export interface PageResult<T> {
  total: number
  list: T[]
}

/** 教师开课申请（CourseApply） */
export interface CourseApply {
  id?: number
  /** 申请人（教师工号）——后端从 token 强制填充，前端不发送 */
  teacherId?: string
  courseCode: string
  courseName: string
  term: string
  collegeId?: number
  credit: number
  classHour: number
  /** 选课容量 = 教学班容量（用户确认二者一致） */
  maxStudent: number
  // ---- 期望时间（可空；审批通过后作为排课申请的默认值） ----
  expectedWeekday?: number
  expectedStartPeriod?: number
  expectedEndPeriod?: number
  expectedStartWeek?: number
  expectedEndWeek?: number
  preferRoomId?: number
  status?: ApplyStatus
  rejectReason?: string
  reviewer?: string
  reviewTime?: string
  /** 审批通过后生成/关联的 course.id —— 排课申请要用它当 courseId */
  createdCourseId?: number
  createTime?: string
  // ---- 展示用冗余字段（不落库） ----
  teacherName?: string
  preferRoomName?: string
  collegeName?: string
  createdCourseCode?: string
}

/** 教师排课申请（ClassTimeApply） */
export interface ClassTimeApply {
  id?: number
  /** 关联已审批的 course.id */
  courseId: number
  teacherId?: string
  /** 1~7 = 周一~周日 */
  weekday: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  roomId?: number
  status?: ApplyStatus
  /**
   * 冲突详情：非空表示该时段有冲突。
   * 教师**提交**时它只是警告（仍会落库待审批）；管理员**审批**时才硬阻断。
   */
  conflictInfo?: string
  rejectReason?: string
  reviewer?: string
  reviewTime?: string
  createTime?: string
  // ---- 展示用冗余字段（不落库） ----
  courseCode?: string
  courseName?: string
  term?: string
  teacherName?: string
  roomName?: string
}

/** 教室（Room） */
export interface Room {
  id?: number
  /** 楼栋，如 教1 */
  building: string
  floorNo?: number
  roomNo?: string
  roomName: string
  capacity: number
  roomType: RoomType
  /** 1=可用 0=停用 */
  status: number
}

/** 一条排课记录 / 课表项（ClassTime） */
export interface ClassTime {
  id: number
  courseId: number
  weekday: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  roomId?: number
  // ---- 展示用冗余字段（不落库） ----
  courseCode?: string
  courseName?: string
  teacherId?: string
  teacherName?: string
  term?: string
  roomName?: string
  roomCapacity?: number
  maxStudent?: number
}

/** 冲突检测里的一条记录（ScheduleService.ConflictItem） */
export interface ConflictItem {
  classTimeId?: number
  courseId?: number
  courseCode?: string
  courseName?: string
  teacherId?: string
  teacherName?: string
  roomId?: number
  roomName?: string
  weekday?: number
  startPeriod?: number
  endPeriod?: number
  startWeek?: number
  endWeek?: number
}

/**
 * 冲突检测结果（ScheduleService.ConflictResult）。
 *
 * 报文里**只有** conflict / teacherConflicts / roomConflicts 三个字段：
 * 后端 `total()` / `describe()` 是 record 派生方法，Jackson 不序列化。
 * 需要"共几条冲突"时用 `utils/schedule.ts` 的 `conflictCount()` 自行相加。
 */
export interface ConflictCheck {
  conflict: boolean
  teacherConflicts: ConflictItem[]
  roomConflicts: ConflictItem[]
}

/** 空闲教室推荐结果（ScheduleService.RoomRecommendation） */
export interface FreeRoomResult {
  found: boolean
  roomId?: number
  roomName?: string
  capacity?: number
  candidates: Room[]
  message?: string
}

/** 冲突检测请求体（POST /class-time/check） */
export interface ConflictCheckForm {
  courseId?: number
  weekday: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  roomId?: number
}

/** 排课申请提交体（POST /class-time/apply） */
export interface ClassTimeApplyForm {
  courseId: number
  weekday: number
  startPeriod: number
  endPeriod: number
  startWeek: number
  endWeek: number
  roomId?: number
}

/** 教室维护的筛选条件 */
export interface RoomQuery {
  building: string
  roomType: RoomType | ''
  minCapacity?: number
  status?: number
}

// ===================== P3：选课轮次 / 选课与补退选 =====================
// 与后端 com.duyell.SelectionRound / SelectionRoundScope 及
// duyell.service.SelectionRoundService / CourseSelectionService 的 record 一一对应，
// 见 docs/教务业务扩展设计.md §2.5、§3.2(7)(8)

/**
 * 选课轮次（SelectionRound）。
 *
 * 「轮次开启」与「时间窗」是**两个独立条件**：`status=1` 只表示管理员打开了开关，
 * 还要看当前时间落没落在窗口里。前端据此自己算「可选 / 只能退 / 只能看」三种状态
 * （报文里没有这种字段，见 utils/selection.ts 的 roundPhaseOf）。
 */
export interface SelectionRound {
  id?: number
  /** 如：2024-2025-1 第一轮选课 */
  roundName: string
  term: string
  /** ISO 本地时间串，如 2030-01-01T08:00:00（后端 LocalDateTime） */
  selectStart?: string
  selectEnd?: string
  /** 补退选窗口，可空 */
  dropStart?: string
  dropEnd?: string
  /** 1=开启 0=关闭 */
  status?: number
  /** 本轮学分上限，可空 = 不限 */
  maxCredits?: number
  createTime?: string
  updateTime?: string
  /** 装配字段（不落库）：空数组表示"不限" */
  scopes?: SelectionRoundScope[]
}

/**
 * 轮次适用范围（SelectionRoundScope）。
 *
 * 三个条件都可空，**空 = 不限**；一条范围记录都不配 = 全年级全专业都可选。
 * 后端**刻意拒绝**全空的范围记录（那等于"不限"，用"没有范围行"表达即可）。
 */
export interface SelectionRoundScope {
  id?: number
  roundId?: number
  /** 限定年级，空 = 不限 */
  grade?: string
  /** 限定专业，空 = 不限 */
  majorId?: number
  /** 限定学院，空 = 不限 */
  collegeId?: number
  // ---- 展示用冗余字段（不落库） ----
  majorName?: string
  collegeName?: string
}

/**
 * 当前选课状态（SelectionRoundService.SelectionStatus）。
 *
 * ⚠️ 报文里**只有**下列字段：后端刻意没有 `readOnly()` 之类的派生方法
 * （Jackson 只序列化 record 组件）。需要"是否只读"就自己推导：
 * `!canSelect && !canDrop`。这条坑在 P1 的 `AuditResult.satisfied()` 上已经踩过一次。
 */
export interface SelectionStatus {
  /** 学期内是否存在已开启的轮次 */
  roundOpen: boolean
  canSelect: boolean
  canDrop: boolean
  roundId?: number
  roundName?: string
  term: string
  /** 本轮学分上限，可空 = 不限 */
  maxCredits?: number
  /** 可直接展示给用户的中文说明 */
  reason?: string
}

/** 一门可选课程（CourseSelectionService.SelectableCourse） */
export interface SelectableCourse {
  course: Course
  /** 本人是否已选 */
  selected: boolean
  /** 现在能不能选；false 时 reason 说明原因 */
  selectable: boolean
  /** **第一条**拦住它的校验原因（轮次/范围/学分上限/已修过/时间冲突/容量），可直接展示 */
  reason?: string | null
}

/** 轮次新建/修改的提交体（POST/PUT /selection-round） */
export interface SelectionRoundForm {
  id?: number
  roundName: string
  term: string
  selectStart: string
  selectEnd: string
  dropStart?: string
  dropEnd?: string
  status?: number
  maxCredits?: number
}

/** 适用范围提交体（POST /selection-round/scope） */
export interface ScopeForm {
  roundId: number
  grade?: string
  majorId?: number
  collegeId?: number
}

/** 轮次列表的筛选条件（空字符串 = 不筛该项） */
export interface SelectionRoundQuery {
  term: string
  status: number | ''
}

// ===================== P4：考试安排 =====================
// 与后端 com.duyell.ExamSchedule 及 duyell.service.ExamService 的 record 一一对应，
// 见 docs/教务业务扩展设计.md §3.2(9)

export type ExamType = 'FINAL' | 'MAKEUP' | 'MIDTERM'

/**
 * 一场考试安排（ExamSchedule）。
 *
 * ⚠️ 报文里**没有** `endTime`、也没有 `upcoming`：它们是普通 Java 方法而不是 getter，
 * Jackson 不序列化。结束时间要在前端用 `examTime + durationMinutes` 算
 * （见 `utils/exam.ts` 的 `examEndTime`）。
 *
 * ⚠️ `typeLabel` 相反，**是**会序列化的现成中文标签（"期末"/"补考"/"期中"），
 * 优先用它，不要自己再映射一套。
 */
export interface ExamSchedule {
  id?: number
  courseId: number
  examType: ExamType
  /** ISO 本地时间串（不带时区），如 2026-09-27T09:00:00 */
  examTime: string
  durationMinutes: number
  /** 考场教室，可空 = 待定 */
  roomId?: number
  /** 座位/考场号段，可空 */
  seatRange?: string
  /** 监考教师（可多人），可空 */
  invigilator?: string
  /** 1=有效 0=作废 */
  status?: number
  remark?: string
  createTime?: string
  updateTime?: string
  // ---- 展示用冗余字段（后端联表带出，不落库） ----
  courseCode?: string
  courseName?: string
  teacherName?: string
  roomName?: string
  term?: string
  /** 后端已给好的中文类型标签 */
  typeLabel?: string
}

/**
 * 一条考试冲突（学生时间冲突 / 考场占用冲突共用这个形状）。
 *
 * 只声明契约保证存在的字段：渲染时不要依赖下面没列的东西，
 * 否则页面上会出现 "undefined"。
 */
export interface ExamConflictItem {
  courseCode?: string
  courseName?: string
  examType?: string
  /** 后端现成的中文类型标签 */
  typeLabel?: string
  /** 冲突那场考试的 ISO 本地时间串 */
  examTime?: string
  durationMinutes?: number
  roomName?: string
}

/**
 * 考试冲突预检结果（POST /exam/check）。
 *
 * 两个维度是**并列**的，页面上要分两组显示：
 * - `roomConflicts` —— 同一考场在同一时段被占用
 * - `studentConflicts` —— 同时选了这两门课的学生会撞考
 *
 * ⚠️ 报文里只有 `conflict` / `roomConflicts` / `studentConflicts`，
 * "共几条"用 `utils/exam.ts` 的 `examConflictCount()` 自行相加。
 */
export interface ExamConflictCheck {
  conflict: boolean
  roomConflicts: ExamConflictItem[]
  studentConflicts: ExamConflictItem[]
}

/**
 * 考试冲突预检请求体（POST /exam/check）。
 *
 * ⚠️ 判据是**半开区间**：`existing.start < newEnd AND existing.end > newStart`。
 * 09:00-11:00 与 11:00-13:00 只是首尾相接，**不算**冲突；
 * 这与排课的节次判据（P2，3-4 与 4-5 共用第 4 节算冲突）**故意不同**，不要统一。
 */
export interface ExamConflictCheckForm {
  courseId?: number
  examTime?: string
  durationMinutes?: number
  roomId?: number
  /** 编辑时排除自己，否则会和自己冲突 */
  excludeExamId?: number
}

/** 考试新建/修改的提交体（POST/PUT /exam；PUT 必须带 id） */
export interface ExamForm {
  id?: number
  courseId?: number
  examType: ExamType
  examTime: string
  durationMinutes: number
  roomId?: number
  seatRange?: string
  invigilator?: string
  status?: number
  remark?: string
}

/** 考试列表的筛选条件（空字符串 = 不筛该项） */
export interface ExamQuery {
  term: string
  examType: ExamType | ''
  courseId?: number
}

// ===================== 学业预警（JW-01 §5） =====================
// 与后端 AcademicWarningService.WarningStatus 一一对应。
// ⚠️ 后端用 record 承载，只序列化 record 组件——所以这里**不要**期待任何"派生字段"。

/** 触发预警的一门未通过课程 */
export interface AcademicWarningCourse {
  courseId: number
  courseCode: string
  courseName: string
  credit: number
  /** 原始总成绩（可能为 null，如只录了补考成绩） */
  totalScore: number | null
  makeupScore: number | null
}

/** 学业预警状态（是否预警 / 是否需要弹通知 / 未通过明细） */
export interface AcademicWarningStatus {
  /** 当前是否达到预警条件 */
  warned: boolean
  /** 是否需要弹通知（达到条件 **且** 超过已确认的水位线） */
  shouldNotify: boolean
  /** 未通过课程学分累计 */
  failedCredits: number
  failedCourseCount: number
  /** 当前阈值（配置项，默认 8 学分） */
  threshold: number
  /** 上次已确认的水位线；从未确认为 null */
  lastNotifiedCredits: number | null
  lastReadAt: string | null
  courses: AcademicWarningCourse[]
}

// ===================== M2：AI 会话与多轮记忆 =====================
// 与后端 AiConversation / AiMessage 一一对应（见 backend .../com/duyell/AiConversation.java）
// ⚠️ 会话 id 是 **UUID 字符串**，不是自增数字：要直接暴露给前端（SSE 事件、URL 参数），
//    自增整数容易被猜。归属校验由服务端强制，前端改 URL 只会得到"无权访问"。

/** AI 会话（列表项） */
export interface AiConversation {
  id: string
  /** 归属用户（学号/工号/用户名）——服务端按 token 赋值，前端只读 */
  userId: string
  /** 创建时的角色：student/teacher/admin（决定用哪套 system prompt） */
  role: string
  title: string
  /** 消息条数；仅用于列表展示 */
  messageCount: number
  createTime: string
  updateTime: string
}

/** 会话中的一条消息（只含面向用户可见的 user/assistant 正文） */
export interface AiMessage {
  id: number
  conversationId: string
  role: 'user' | 'assistant'
  content: string
  createTime: string
}

/** `GET /ai/conversations/{id}/messages` 的返回体（标题 + 消息一次带回） */
export interface AiConversationDetail {
  conversation: AiConversation
  messages: AiMessage[]
}

/** 确认卡片里的一行参数（键值都已转成字符串，避免模板里再做判空） */
export interface ChatConfirmArg {
  key: string
  value: string
}

/**
 * 聊天区渲染用的消息模型（前端本地态，不落库）。
 *
 * 与 {@link AiMessage} 的区别：后者是"已持久化的对话正文"，而这里还要承载**流式中间态**
 * （token 增量、状态提示、确认卡片、错误），所以 type 比 role 更细。
 */
export interface ChatMsg {
  role: 'user' | 'assistant' | 'system'
  type: 'text' | 'token' | 'status' | 'error' | 'confirm'
  content: string
  /** 危险操作确认卡片携带的字段（type === 'confirm' 时有效） */
  confirmId?: string
  confirmTitle?: string
  confirmArgs?: ChatConfirmArg[]
  decided?: boolean
  approved?: boolean
  expired?: boolean
}

/**
 * 后端 SSE 事件协议（与 AiChatService 发送的 Map 一一对应）。
 *
 * 集中定义可保证前后端语义一致，避免 `if (type === ...)` 的判断散落各处。
 * `conversation` 是 M2 新增：**流的第一个事件**，用于把（可能是服务端兜底新建的）会话 id 告知前端。
 */
export type AgentSseEvent =
  | { type: 'token'; content: string }
  | { type: 'status'; content: string }
  | { type: 'error'; content: string }
  | { type: 'conversation'; conversationId: string }
  | {
      type: 'confirm'
      confirmId: string
      tool?: string
      displayName?: string
      args?: Record<string, unknown>
      timeoutSeconds?: number
    }
  | { type: 'confirm_result'; confirmId: string; approved?: boolean; expired?: boolean }
  | { type: 'done' }
