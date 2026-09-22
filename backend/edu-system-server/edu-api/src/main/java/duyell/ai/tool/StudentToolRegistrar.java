package duyell.ai.tool;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.*;
import duyell.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import utils.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 学生角色的 Agent 工具注册。
 *
 * <p><b>安全前提（用户明确，不得放宽）</b>：学生只能看自己的数据。
 * 所有工具一律以 {@code userId}（＝学号，来自 token）为作用域，
 * <b>绝不</b>接受 {@code args} 里传来的学号——否则模型（或提示注入）就能查别人。
 *
 * <p><b>两条给模型的契约</b>：
 * <ol>
 *   <li>每个工具的 {@code description} 必须说清 <b>「什么时候用」</b> 与
 *       <b>「可选参数省略时是什么行为」</b>。本项目踩过这个坑：描述里没写省略行为，
 *       模型会反问"你要查哪个学期"而不是直接作答（见 {@code docs/开发记录.md}）。</li>
 *   <li>执行体一律返回 JSON 字符串；业务异常转成 {@code {"error":"<中文原因>"}} 返回，
 *       <b>不抛出</b>——抛出会变成通用失败载荷，模型无法据此向用户解释。
 *       非业务异常只回通用文案，<b>不外泄 SQL/表名/堆栈</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>不要直接 return 服务层的 record</b>：Jackson 只序列化 record 组件，
 * record 上的派生方法（如 {@code AuditResult.satisfied()}、{@code creditGap()}）**不进报文**。
 * 本类因此一律手工构造 {@link LinkedHashMap}，派生值在 Java 里算好。
 * 这个坑在本仓库已经造成过两次真实缺陷（P1 的 {@code satisfied}、P3 的 {@code readOnly}）。
 *
 * @author duyell
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentToolRegistrar implements InitializingBean {

    private final ToolRegistry registry;
    private final CourseSelectionMapper courseSelectionMapper;
    private final ScoreMapper scoreMapper;
    private final CourseMapper courseMapper;
    private final EvaluationMapper evaluationMapper;
    private final StudentMapper studentMapper;
    private final ObjectMapper objectMapper;
    private final SysUserMapper sysUserMapper;
    private final CourseSelectionService courseSelectionService;

    // ---------- P5 新增：业务扩展阶段的服务（培养计划/绩点/学分/考试/选课/排课） ----------
    private final TrainingPlanService trainingPlanService;
    private final CreditService creditService;
    private final GpaService gpaService;
    private final ExamService examService;
    private final SelectionRoundService selectionRoundService;
    private final ScheduleService scheduleService;
    private final AcademicWarningService academicWarningService;
    private final ClassTimeMapper classTimeMapper;

    private static final String[] WEEKDAY_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ==================================================================================
    // 注册入口
    // ==================================================================================

    @Override
    public void afterPropertiesSet() {
        registerExistingTools();
        registerAcademicTools();
    }

    // ==================================================================================
    // 既有工具（保持原样，勿改动行为）
    // ==================================================================================

    private void registerExistingTools() {
        registry.register("student", new ToolDefinition(
                "get_my_courses", "我的已选课程", "获取当前学生已选的课程列表",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(userId);
                    // IN 查询一次取出全部课程，避免 N+1
                    List<Integer> courseIds = selections.stream()
                            .map(CourseSelection::getCourseId)
                            .toList();
                    List<Course> courses = courseIds.isEmpty() ? List.of() : courseMapper.selectByIds(courseIds);
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Course course : courses) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("courseId", course.getId());
                        item.put("courseName", course.getCourseName());
                        item.put("teacherName", course.getTeacherName());
                        item.put("term", course.getTerm());
                        item.put("credit", course.getCredit());
                        result.add(item);
                    }
                    return objectMapper.writeValueAsString(result);
                }
        ));

        registry.register("student", new ToolDefinition(
                "get_my_scores", "我的成绩", "获取当前学生的成绩",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<Score> scores = scoreMapper.list(null, Integer.valueOf(userId), null);
                    return objectMapper.writeValueAsString(scores);
                }
        ));

        registry.register("student", new ToolDefinition(
                "select_course", "选课", "学生选课，添加课程到已选列表",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    // 复用选课服务：事务内完成查重、容量校验与并发控制
                    try {
                        courseSelectionService.select(courseId, userId);
                        return "{\"message\":\"选课成功\"}";
                    } catch (RuntimeException e) {
                        return "{\"message\":\"" + e.getMessage() + "\"}";
                    }
                }
        ));

        registry.register("student", new ToolDefinition(
                "drop_course", "退课", "学生退课，从已选列表中移除课程（可通过重新选课恢复）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                RiskLevel.WRITE,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    courseSelectionMapper.delete(courseId, userId);
                    return "{\"message\":\"退课成功\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "get_course_list", "可选课程列表",
                "查看可选课程列表。不传 courseName 时返回全部可选课程；"
                        + "用户说\"有哪些课能选\"\"列出所有可选课程\"时，直接调用本工具且不要追问课程名称。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseName", Map.of("type", "string",
                                        "description", "课程名称关键词，用于筛选；省略则返回全部")
                        )
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    String courseName = (String) args.getOrDefault("courseName", null);
                    List<Course> courses = courseMapper.list(courseName, null, null, null, null, null, null);
                    return objectMapper.writeValueAsString(courses);
                }
        ));

        registry.register("student", new ToolDefinition(
                "evaluate_teacher", "教学评价",
                "对某门课程的教师进行教学评价（**5 星制**，1~5 分；提交后不可修改）。"
                        + "content 省略时只提交评分、不附文字评价。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID"),
                                "teacherId", Map.of("type", "string", "description", "教师工号"),
                                "score", Map.of("type", "integer", "minimum", 1, "maximum", 5,
                                        "description", "评分（5 星制，1~5 的整数）"),
                                "content", Map.of("type", "string", "description", "评价内容；省略则不附文字评价")
                        ),
                        "required", List.of("courseId", "teacherId", "score")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    String teacherId = (String) args.get("teacherId");
                    Integer scoreVal = Integer.valueOf(args.get("score").toString());
                    String content = (String) args.getOrDefault("content", "");

                    TeacherEvaluation eval = new TeacherEvaluation();
                    eval.setCourseId(courseId);
                    eval.setStudentId(userId);
                    eval.setTeacherId(teacherId);
                    eval.setScore(scoreVal);
                    eval.setContent(content);
                    evaluationMapper.add(eval);
                    return "{\"message\":\"评价提交成功\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "check_evaluation", "评价状态检查", "检查某门课程是否已经评价过",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    TeacherEvaluation existing = evaluationMapper.selectByCourseAndStudent(courseId, userId);
                    if (existing != null) {
                        return "{\"evaluated\":true,\"message\":\"该课程已评价\"}";
                    }
                    return "{\"evaluated\":false,\"message\":\"该课程尚未评价\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "get_my_evaluations", "我的评价", "获取当前学生提交的所有教学评价",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<TeacherEvaluation> evaluations = evaluationMapper.list(null, userId, null);
                    return objectMapper.writeValueAsString(evaluations);
                }
        ));
    }

    // ==================================================================================
    // P5 新增的 8 个工具（全部 READ_ONLY，且都只读本人数据）
    // ==================================================================================

    private void registerAcademicTools() {

        // ---------------------------------------------------------------- 培养计划
        registry.register("student", new ToolDefinition(
                "get_my_training_plan", "我的培养方案",
                "查看**本人适用**的培养方案（专业 + 入学年级推导）及其全部课程明细、必修/选修构成。"
                        + "学生问「我的培养方案是什么」「我要修哪些课」「大一上该上什么」时使用。"
                        + "本工具**没有参数**，直接调用即可，不要追问专业或年级——系统按学籍自动定位。"
                        + "若该专业年级尚未录入方案，返回里 plan 为 null 且 message 说明原因，请照实告知用户。",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("studentId", userId);

                    Student placement = trainingPlanService.getStudentPlacement(userId);
                    out.put("placement", placementMap(placement));

                    TrainingPlan plan = trainingPlanService.resolvePlanForStudent(userId);
                    if (plan == null) {
                        out.put("plan", null);
                        out.put("courseCount", 0);
                        out.put("courses", List.of());
                        out.put("message", "暂无适用于你的培养方案（" + placementDesc(placement)
                                + "）。请联系管理员录入本专业本年级的培养方案。");
                        return out;
                    }

                    List<PlanCourse> planCourses = trainingPlanService.listPlanCourses(plan.getId());
                    List<Map<String, Object>> courses = new ArrayList<>(planCourses.size());
                    int requiredCount = 0;
                    int electiveCount = 0;
                    BigDecimal requiredCredit = BigDecimal.ZERO;
                    BigDecimal electiveCredit = BigDecimal.ZERO;
                    for (PlanCourse pc : planCourses) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("courseCode", pc.getCourseCode());
                        item.put("courseName", pc.getCourseName());
                        item.put("category", pc.getCategory());
                        item.put("categoryText", PlanCourse.CATEGORY_REQUIRED.equals(pc.getCategory()) ? "必修" : "选修");
                        item.put("suggestSemester", pc.getSuggestSemester());
                        item.put("suggestSemesterText", semesterText(pc.getSuggestSemester()));
                        item.put("credit", pc.getCredit());
                        courses.add(item);
                        BigDecimal credit = pc.getCredit() == null ? BigDecimal.ZERO : pc.getCredit();
                        if (pc.isRequired()) {
                            requiredCount++;
                            requiredCredit = requiredCredit.add(credit);
                        } else {
                            electiveCount++;
                            electiveCredit = electiveCredit.add(credit);
                        }
                    }

                    out.put("plan", planMap(plan));
                    out.put("courseCount", courses.size());
                    out.put("requiredCount", requiredCount);
                    out.put("electiveCount", electiveCount);
                    out.put("planRequiredCreditSum", requiredCredit);
                    out.put("planElectiveCreditSum", electiveCredit);
                    out.put("courses", courses);
                    out.put("message", "适用方案《" + plan.getPlanName() + "》：共 " + courses.size()
                            + " 门课（必修 " + requiredCount + " 门 / 选修 " + electiveCount
                            + " 门）；毕业要求总学分 " + plain(plan.getTotalCredits())
                            + "，其中必修 " + plain(plan.getRequiredCredits())
                            + "、选修 " + plain(plan.getElectiveCredits()) + "。"
                            + "必修必须逐门通过，选修只看学分总和是否达标。");
                    return out;
                })
        ));

        // ---------------------------------------------------------------- 毕业学分审核
        registry.register("student", new ToolDefinition(
                "audit_my_graduation", "毕业学分审核",
                "按培养方案核对**本人**的毕业学分完成情况：已修学分、必修是否逐门通过、选修学分是否达标、"
                        + "总学分缺口，以及还没通过的必修课清单。"
                        + "学生问「我学分够毕业吗」「我还差多少学分」「我还有哪门必修没过」时使用。"
                        + "本工具**没有参数**，直接调用即可。"
                        + "返回里的 satisfied/creditGap 是服务端算好的派生值（record 派生方法不会进 JSON，此处已手工补上）。",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> auditMap(userId))
        ));

        // ---------------------------------------------------------------- 绩点与排名
        registry.register("student", new ToolDefinition(
                "get_my_gpa", "我的绩点与排名",
                "查看**本人**的平均学分绩点、逐门课程绩点明细，以及本人在**同专业同年级**内的排名。"
                        + "学生问「我的绩点是多少」「我绩点多高」「我在专业里排第几」「这门课算多少绩点」时使用。"
                        + "term 参数省略时返回**全部学期的累计**绩点；只有用户明确说了某个学期（如「这学期」「2024-2025-1」）"
                        + "才传 term，不要为了「确认学期」而反问。排名只返回本人名次，不含他人明细。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "term", Map.of("type", "string",
                                        "description", "学期，如 2024-2025-1；**省略则返回全部学期的累计绩点**")
                        )
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> {
                    String term = str(args, "term");
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("studentId", userId);
                    out.put("term", term == null ? "ALL" : term);
                    out.put("termText", term == null ? "全部学期（累计）" : term);

                    GpaService.GpaResult gpa = gpaService.calcGpa(userId, term);
                    out.put("gpa", gpa.gpa());
                    out.put("totalCredit", gpa.totalCredit());
                    out.put("totalGradePoint", gpa.totalGradePoint());
                    out.put("passedCount", gpa.passedCount());

                    List<Map<String, Object>> details = new ArrayList<>();
                    for (GpaService.CourseGpa d : gpa.details()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("courseCode", d.courseCode());
                        item.put("courseName", d.courseName());
                        item.put("credit", d.credit());
                        item.put("rawScore", d.rawScore());
                        item.put("usedScore", d.usedScore());
                        item.put("gradePoint", d.gradePoint());
                        item.put("fromMakeup", d.fromMakeup());
                        details.add(item);
                    }
                    out.put("details", details);

                    GpaService.RankResult rank = gpaService.rankInMajor(userId);
                    Map<String, Object> rankMap = new LinkedHashMap<>();
                    rankMap.put("ranked", rank.ranked());
                    rankMap.put("majorName", rank.majorName());
                    rankMap.put("grade", rank.grade());
                    rankMap.put("rank", rank.rank());
                    rankMap.put("total", rank.total());
                    rankMap.put("topGpa", rank.topGpa());
                    rankMap.put("passLine", rank.passLine());
                    rankMap.put("rankText", rank.ranked()
                            ? "同专业同年级第 " + rank.rank() + " 名 / 共 " + rank.total() + " 人"
                            : "暂无排名（缺少专业或年级信息）");
                    out.put("rank", rankMap);

                    // 口径说明：让模型能解释「为什么这门课绩点是 0」，否则它会自己编规则。
                    // passLine 在无法排名时可能为 null，不能直接拼成"及格线 0 分"。
                    String passLineText = rank.passLine() == null ? "60" : plain(rank.passLine());
                    out.put("gradePointRule", "及格线 " + passLineText
                            + " 分：低于及格线绩点为 0；否则 绩点 = 分数 / 10 - 5（补考通过一律按 60 分计，即绩点 1.0）。"
                            + "平均学分绩点 = Σ(单科绩点 × 学分) / Σ(学分)，只统计已通过的课程。");
                    out.put("message", "平均学分绩点 " + plain(gpa.gpa())
                            + "，计入学分 " + plain(gpa.totalCredit())
                            + "，已通过 " + gpa.passedCount() + " 门。"
                            + rankMap.get("rankText"));
                    return out;
                })
        ));

        // ---------------------------------------------------------------- 推荐课程（多步推理）
        registry.register("student", new ToolDefinition(
                "recommend_courses", "推荐可选课程",
                "根据**培养方案缺口**推荐现在可以选的课：先算毕业审核，再看这些缺口课程里哪些当前可选，"
                        + "并说明每门推荐它的理由；暂时不能选的也会说明被哪一条选课规则挡住。"
                        + "学生问「我该选什么课」「推荐几门课」「我还差哪些课」时使用。"
                        + "term 参数省略时**自动覆盖所有已配置选课轮次的学期**（即「现在能选什么」），"
                        + "不要为了「确认学期」而反问；只有用户明确指定学期时才传 term。"
                        + "若没有培养方案、没有缺口、或轮次未开放导致什么都选不了，返回里的 message 会说明原因，请照实转述。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "term", Map.of("type", "string",
                                        "description", "学期，如 2024-2025-1；**省略则覆盖所有已配置轮次的学期**")
                        )
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> recommendCourses(args, userId))
        ));

        // ---------------------------------------------------------------- 我的考试
        registry.register("student", new ToolDefinition(
                "get_my_exams", "我的考试安排",
                "查看**本人已选课程**的考试安排（考试时间、时长、考场、座位号段）。"
                        + "学生问「我下周有什么考试」「考试安排出来了吗」「某门课什么时候考」时使用。"
                        + "term 省略时返回**全部学期**的考试；upcoming 省略时默认 false（含已考完的），"
                        + "学生问「接下来/下周/还有哪些考试」时应传 upcoming=true 只看未开考的。"
                        + "只返回本人已选课程的考试，不会包含没选的课。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "term", Map.of("type", "string",
                                        "description", "学期，如 2024-2025-1；**省略则返回全部学期**"),
                                "upcoming", Map.of("type", "boolean",
                                        "description", "true 只看还没开考的；**省略默认为 false**（含已考完的）")
                        )
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> {
                    String term = str(args, "term");
                    boolean upcoming = bool(args, "upcoming", false);
                    List<ExamSchedule> exams = examService.listByStudent(userId, term, upcoming);

                    List<Map<String, Object>> rows = new ArrayList<>(exams.size());
                    for (ExamSchedule e : exams) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("examId", e.getId());
                        item.put("courseId", e.getCourseId());
                        item.put("courseCode", e.getCourseCode());
                        item.put("courseName", e.getCourseName());
                        item.put("examType", e.getExamType());
                        item.put("examTypeText", e.getTypeLabel());
                        item.put("examTime", e.getExamTime() == null ? null : e.getExamTime().toString());
                        item.put("examTimeText", format(e.getExamTime()));
                        item.put("durationMinutes", e.getDurationMinutes());
                        // endTime 是 POJO 上的非 getter 方法，不进报文，这里手工补
                        item.put("endTime", e.endTime() == null ? null : e.endTime().toString());
                        item.put("endTimeText", format(e.endTime()));
                        item.put("roomName", e.getRoomName());
                        item.put("roomText", e.getRoomName() == null ? "考场待定" : e.getRoomName());
                        item.put("seatRange", e.getSeatRange());
                        item.put("term", e.getTerm());
                        rows.add(item);
                    }

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("studentId", userId);
                    out.put("term", term == null ? "ALL" : term);
                    out.put("upcomingOnly", upcoming);
                    out.put("count", rows.size());
                    out.put("exams", rows);
                    if (rows.isEmpty()) {
                        out.put("message", upcoming
                                ? "你目前没有还没开考的考试。" + (term == null ? "" : "（学期 " + term + "）")
                                : "暂无考试安排。" + (term == null ? "" : "（学期 " + term + "）")
                                  + "注意：只统计你**已选课程**的考试，未选的课不会出现在这里。");
                    } else {
                        out.put("message", "共 " + rows.size() + " 场考试，按考试时间升序，最近一场在 "
                                + rows.get(0).get("examTimeText") + "（" + rows.get(0).get("courseName")
                                + " " + rows.get(0).get("examTypeText") + "，"
                                + rows.get(0).get("roomText") + "）。");
                    }
                    return out;
                })
        ));

        // ---------------------------------------------------------------- 选课是否开放
        registry.register("student", new ToolDefinition(
                "get_selection_status", "选课开放状态",
                "查询**本人**现在能不能选课/退课，以及原因；判定同时考虑管理员开关、适用范围和选课/补退选时间窗。"
                        + "学生问「现在能选课吗」「选课开始了吗」「还能退课吗」「什么时候能选」时使用。"
                        + "term 省略时**返回所有已配置轮次的学期的状态**（这样不用先反问用户是哪学期）；"
                        + "只有用户明确说了某个学期才传 term。reason 是可以直接转述给用户的中文说明。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "term", Map.of("type", "string",
                                        "description", "学期，如 2024-2025-1；**省略则返回所有已配置轮次的学期**")
                        )
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> {
                    String term = str(args, "term");
                    List<String> terms = new ArrayList<>();
                    if (term != null) {
                        terms.add(term);
                    } else {
                        // 省略学期：覆盖所有配置了轮次的学期，避免"先反问用户是哪学期"
                        for (SelectionRound r : selectionRoundService.list(null, null)) {
                            if (r.getTerm() != null && !terms.contains(r.getTerm())) {
                                terms.add(r.getTerm());
                            }
                        }
                    }

                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (String t : terms) {
                        SelectionRoundService.SelectionStatus st = selectionRoundService.statusFor(userId, t);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("term", t);
                        item.put("roundOpen", st.roundOpen());
                        item.put("canSelect", st.canSelect());
                        item.put("canDrop", st.canDrop());
                        item.put("roundName", st.roundName());
                        item.put("maxCredits", st.maxCredits());
                        item.put("reason", st.reason());
                        item.put("advice", selectionAdvice(st));
                        rows.add(item);
                    }

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("studentId", userId);
                    out.put("queriedTerm", term == null ? "ALL" : term);
                    out.put("count", rows.size());
                    out.put("statuses", rows);
                    if (rows.isEmpty()) {
                        out.put("message", "系统里还没有配置任何选课轮次，因此目前不能选课，只能查看课程。");
                    } else {
                        boolean anySelect = rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("canSelect")));
                        out.put("anyCanSelect", anySelect);
                        out.put("message", anySelect
                                ? "你当前可以选课。"
                                : "你当前不能选课：" + rows.get(0).get("reason"));
                    }
                    return out;
                })
        ));

        // ---------------------------------------------------------------- 我的课表
        registry.register("student", new ToolDefinition(
                "list_my_class_times", "我的课表",
                "查看**本人已选课程**的上课时间（星期几、第几节、第几周、教室）。"
                        + "学生问「我的课表是什么」「我周一有什么课」「这门课什么时候上」时使用。"
                        + "term 省略时返回**全部学期**已选课程的课表；只有用户明确说了学期才传 term。"
                        + "已选但还没排课的课程也会出现在结果里（slots 为空并有提示），不会凭空消失。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "term", Map.of("type", "string",
                                        "description", "学期，如 2024-2025-1；**省略则返回全部学期**")
                        )
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> {
                    String term = str(args, "term");
                    List<Course> myCourses = courseSelectionService.listMyCourses(userId);

                    List<Map<String, Object>> courseRows = new ArrayList<>();
                    int totalSlots = 0;
                    int unscheduled = 0;
                    for (Course course : myCourses) {
                        if (term != null && !term.equals(course.getTerm())) {
                            continue;
                        }
                        List<Map<String, Object>> slots = new ArrayList<>();
                        for (ClassTime ct : scheduleService.listByCourse(course.getId())) {
                            slots.add(classTimeMap(ct));
                        }
                        totalSlots += slots.size();
                        if (slots.isEmpty()) {
                            unscheduled++;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("courseId", course.getId());
                        row.put("courseCode", course.getCourseCode());
                        row.put("courseName", course.getCourseName());
                        row.put("teacherName", course.getTeacherName());
                        row.put("term", course.getTerm());
                        row.put("slotCount", slots.size());
                        row.put("slots", slots);
                        if (slots.isEmpty()) {
                            row.put("note", "该课程尚未排课，暂时没有上课时间与教室");
                        }
                        courseRows.add(row);
                    }

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("studentId", userId);
                    out.put("term", term == null ? "ALL" : term);
                    out.put("courseCount", courseRows.size());
                    out.put("slotCount", totalSlots);
                    out.put("unscheduledCount", unscheduled);
                    out.put("courses", courseRows);
                    if (courseRows.isEmpty()) {
                        out.put("message", "你还没有已选课程，因此没有课表。" + (term == null ? "" : "（学期 " + term + "）"));
                    } else {
                        out.put("message", "已选 " + courseRows.size() + " 门课，共 " + totalSlots
                                + " 条上课时间" + (unscheduled > 0 ? "；其中 " + unscheduled + " 门尚未排课" : "")
                                + "。");
                    }
                    return out;
                })
        ));

        // ---------------------------------------------------------------- 学业预警（JW-01 §5）
        registry.register("student", new ToolDefinition(
                "get_my_academic_warning", "我的学业预警",
                "查询本人学业预警状态：**未通过课程**（考核不及格且补考未通过）的**学分累计**是否达到阈值，"
                        + "以及未通过课程清单。学生问「我有没有学业预警」「我挂了几门」「多少学分没过」时使用。"
                        + "无参数，只读。预警**只是提示**，不会自动产生留级、退学等任何处理；"
                        + "严重情况的处理由教务人工决定。",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> {
                    AcademicWarningService.WarningStatus st = academicWarningService.statusFor(userId);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("warned", st.warned());
                    out.put("failedCredits", st.failedCredits());
                    out.put("failedCourseCount", st.failedCourseCount());
                    out.put("threshold", st.threshold());
                    out.put("courses", st.courses());
                    String credits = st.failedCredits().stripTrailingZeros().toPlainString();
                    String limit = st.threshold().stripTrailingZeros().toPlainString();
                    out.put("message", st.warned()
                            ? "你已触发学业预警：未通过 " + st.failedCourseCount() + " 门课程，累计 " + credits
                              + " 学分，已达到阈值 " + limit + " 学分。建议尽快与辅导员沟通重修安排。"
                            : "你当前未触发学业预警：未通过课程累计 " + credits + " 学分，未达到阈值 " + limit + " 学分。");
                    return out;
                })
        ));

        // ---------------------------------------------------------------- 冲突检查
        registry.register("student", new ToolDefinition(
                "check_time_conflict", "检查上课时间冲突",
                "检查**某门课**与本人现有课表是否有上课时间冲突（同一学期、同一星期、节次与周次区间重叠）。"
                        + "学生问「这门课和我的课冲突吗」「我能选这门课吗（时间上）」「帮我查下会不会撞课」时使用。"
                        + "课程用 courseId / courseCode / courseName **任选其一**指定（课程代码如 CS101 最稳）；"
                        + "三者都不传时返回错误提示，不会瞎猜课程。"
                        + "⚠️ 用户通常只会说课程名，此时直接传 courseName，**不要**自己编一个 courseId；"
                        + "同一课程代码有多个学期时会取最近学期，返回里会回显实际检查的学期与候选列表。"
                        + "⚠️ 判据是**闭区间**：第 3-4 节与第 4-5 节共用第 4 节，算冲突；"
                        + "这与考试时间判据（半开区间，11:00 结束 vs 11:00 开始不算冲突）**不同**，不要混用。"
                        + "若该课程尚未排课，则不可能冲突，返回里会明确说明。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer",
                                        "description", "课程ID（与 courseCode/courseName 任选其一；已知 ID 时优先用它）"),
                                "courseCode", Map.of("type", "string",
                                        "description", "课程代码，如 CS101（与 courseId/courseName 任选其一）"),
                                "courseName", Map.of("type", "string",
                                        "description", "课程名称，用户只说课名时用它（与 courseId/courseCode 任选其一）")
                        ),
                        "required", List.of()
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> json(() -> {
                    CourseResolution resolved = resolveCourse(args);
                    if (resolved.error() != null) {
                        return resolved.error();
                    }
                    Course course = resolved.course();
                    Integer courseId = course.getId();

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("courseId", courseId);
                    out.put("courseCode", course.getCourseCode());
                    out.put("courseName", course.getCourseName());
                    out.put("term", course.getTerm());
                    out.putAll(resolved.meta());
                    if (course.getTeacherName() != null) {
                        out.put("teacherName", course.getTeacherName());
                    }

                    List<ClassTime> selfSlots = scheduleService.listByCourse(courseId);
                    List<Map<String, Object>> selfMaps = new ArrayList<>();
                    for (ClassTime ct : selfSlots) {
                        selfMaps.add(classTimeMap(ct));
                    }
                    out.put("courseSlots", selfMaps);

                    // 候选课程没有排课 → 结构上不可能冲突，明确说明而不是报"无冲突"了事
                    if (selfSlots.isEmpty()) {
                        out.put("conflict", false);
                        out.put("conflicts", List.of());
                        out.put("message", "《" + safeName(course) + "》尚未排课，因此不会与你已选课程发生时间冲突"
                                + "（但排课后可能产生冲突，选课前可再查一次）。");
                        return out;
                    }

                    List<ClassTime> conflicts = classTimeMapper.selectStudentConflicts(userId, courseId);
                    List<Map<String, Object>> conflictMaps = new ArrayList<>();
                    for (ClassTime ct : conflicts) {
                        Map<String, Object> item = classTimeMap(ct);
                        item.put("conflictText", slotText(ct));
                        conflictMaps.add(item);
                    }
                    out.put("conflict", !conflicts.isEmpty());
                    out.put("conflictCount", conflicts.size());
                    out.put("conflicts", conflictMaps);
                    out.put("message", conflicts.isEmpty()
                            ? "《" + safeName(course) + "》(" + slotText(selfSlots.get(0)) + ") 与你已选课程没有时间冲突。"
                            : "《" + safeName(course) + "》与已选课程时间冲突：" + joinConflicts(conflicts)
                              + "。若仍要选这门课，需要先退掉冲突的课程。");
                    return out;
                })
        ));
    }

    // ==================================================================================
    // 组装辅助
    // ==================================================================================

    /** 毕业审核：手工摊平 + 补上 record 派生方法（satisfied / creditGap 不进 JSON） */
    private Map<String, Object> auditMap(String userId) {
        CreditService.AuditResult audit = creditService.auditGraduation(userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", userId);
        out.put("planFound", audit.planFound());
        out.put("planName", audit.planName());
        out.put("earnedCredits", audit.earnedCredits());
        out.put("requiredCredits", audit.requiredCredits());
        out.put("earnedRequiredCredit", audit.earnedRequiredCredit());
        out.put("electiveCredits", audit.electiveCredits());
        out.put("earnedElectiveCredit", audit.earnedElectiveCredit());
        out.put("totalCredits", audit.totalCredits());
        out.put("creditSatisfied", audit.creditSatisfied());
        out.put("requiredSatisfied", audit.requiredSatisfied());
        out.put("electiveSatisfied", audit.electiveSatisfied());

        // ⚠️ 派生值必须自己算：record 的 satisfied()/creditGap() 不会被 Jackson 序列化
        boolean satisfied = audit.planFound() && audit.creditSatisfied()
                && audit.requiredSatisfied() && audit.electiveSatisfied();
        out.put("satisfied", satisfied);
        out.put("creditGap", audit.creditGap());

        List<Map<String, Object>> missing = new ArrayList<>();
        for (CreditService.MissingCourse mc : audit.missingRequired()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseCode", mc.courseCode());
            item.put("courseName", mc.courseName());
            item.put("credit", mc.credit());
            item.put("suggestSemester", mc.suggestSemester());
            item.put("suggestSemesterText", semesterText(mc.suggestSemester()));
            item.put("state", mc.state());
            item.put("stateText", CreditService.MissingCourse.STATE_FAILED.equals(mc.state())
                    ? "已修但未通过（需补考/重修）" : "未修读（需选课）");
            missing.add(item);
        }
        out.put("missingRequired", missing);
        out.put("missingRequiredCount", missing.size());
        out.put("unmetElectiveCodes", audit.unmetElectiveCodes());
        out.put("unmetElectiveCount", audit.unmetElectiveCodes() == null ? 0 : audit.unmetElectiveCodes().size());

        if (!audit.planFound()) {
            out.put("message", "没有找到你适用的培养方案，无法核算毕业学分。请联系管理员录入本专业本年级的培养方案。");
        } else if (satisfied) {
            out.put("message", "毕业学分要求已全部达成：已修 " + plain(audit.earnedCredits())
                    + " 学分，必修全部通过，选修学分达标。");
        } else {
            out.put("message", "尚未满足毕业学分要求：已修 " + plain(audit.earnedCredits())
                    + " / 要求 " + plain(audit.totalCredits()) + " 学分，缺口 " + plain(audit.creditGap())
                    + " 学分；未通过必修 " + missing.size() + " 门，待修选修 "
                    + (audit.unmetElectiveCodes() == null ? 0 : audit.unmetElectiveCodes().size()) + " 门。");
        }
        return out;
    }

    /**
     * 推荐课程：培养计划缺口 ∩ 当前可选。
     *
     * <p>多步推理：audit → 缺口代码集合 → 逐学期取可选课程 → 命中缺口的课分"可立即选"与"被挡住"两类。
     * 被挡住的也返回（带 reason），这样模型能回答"为什么现在选不了"而不是干巴巴一句没有可选课。
     */
    private Map<String, Object> recommendCourses(Map<String, Object> args, String userId) {
        String term = str(args, "term");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", userId);

        CreditService.AuditResult audit = creditService.auditGraduation(userId);
        out.put("auditSummary", summarizeAudit(audit));

        if (!audit.planFound()) {
            out.put("recommendations", List.of());
            out.put("blocked", List.of());
            out.put("message", "没有找到你适用的培养方案，无法按方案缺口推荐课程。请联系管理员录入培养方案；"
                    + "如果你想看本学期开了哪些课，可以问我「有哪些课能选」。");
            return out;
        }

        // 缺口集合：未通过的必修（按课程代码）+ 尚未通过的选修代码
        Map<String, String> gapType = new LinkedHashMap<>();
        for (CreditService.MissingCourse mc : audit.missingRequired()) {
            gapType.put(mc.courseCode(), "REQUIRED");
        }
        if (audit.unmetElectiveCodes() != null) {
            for (String code : audit.unmetElectiveCodes()) {
                gapType.putIfAbsent(code, "ELECTIVE");
            }
        }
        if (gapType.isEmpty()) {
            out.put("recommendations", List.of());
            out.put("blocked", List.of());
            out.put("message", "培养方案要求的课程你都已通过，没有需要补的课。");
            return out;
        }
        out.put("gapCodes", new ArrayList<>(gapType.keySet()));
        out.put("gapCount", gapType.size());

        // 学期范围：显式给了就用它；否则覆盖所有已配置轮次的学期（＝「现在能选什么」）
        List<String> terms = new ArrayList<>();
        if (term != null) {
            terms.add(term);
        } else {
            for (SelectionRound r : selectionRoundService.list(null, null)) {
                if (r.getTerm() != null && !terms.contains(r.getTerm())) {
                    terms.add(r.getTerm());
                }
            }
        }
        if (terms.isEmpty()) {
            out.put("recommendations", List.of());
            out.put("blocked", List.of());
            out.put("message", "系统里还没有配置任何选课轮次，因此现在没有可选的课。请联系管理员开启选课。");
            return out;
        }
        out.put("scannedTerms", terms);

        List<Map<String, Object>> recommendations = new ArrayList<>();
        List<Map<String, Object>> blocked = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();
        String firstBlockReason = null;

        for (String t : terms) {
            for (CourseSelectionService.SelectableCourse sc : courseSelectionService.listSelectableCourses(userId, t)) {
                Course c = sc.course();
                if (c == null || c.getCourseCode() == null || !gapType.containsKey(c.getCourseCode())) {
                    continue;
                }
                if (sc.selected()) {
                    continue; // 已经选了，不必再推荐
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("courseId", c.getId());
                item.put("courseCode", c.getCourseCode());
                item.put("courseName", c.getCourseName());
                item.put("credit", c.getCredit());
                item.put("term", c.getTerm());
                item.put("teacherName", c.getTeacherName());
                item.put("maxStudent", c.getMaxStudent());
                item.put("gapType", gapType.get(c.getCourseCode()));
                item.put("gapTypeText", "REQUIRED".equals(gapType.get(c.getCourseCode())) ? "必修（必须通过）" : "选修（凑学分）");

                if (sc.selectable()) {
                    if (seenCodes.add(c.getCourseCode() + "@" + c.getTerm())) {
                        item.put("reason", "该课程属于培养方案中你尚未通过的" + item.get("gapTypeText") + "，且当前可选。");
                        recommendations.add(item);
                    }
                } else {
                    if (firstBlockReason == null && sc.reason() != null) {
                        firstBlockReason = sc.reason();
                    }
                    item.put("blockedBy", sc.reason());
                    blocked.add(item);
                }
            }
        }

        out.put("recommendationCount", recommendations.size());
        out.put("recommendations", recommendations);
        out.put("blockedCount", blocked.size());
        out.put("blocked", blocked);

        if (!recommendations.isEmpty()) {
            out.put("message", "培养方案里你还差 " + gapType.size() + " 门课；其中 " + recommendations.size()
                    + " 门当前可以选" + (blocked.isEmpty() ? "。" : "，" + blocked.size() + " 门暂时选不了。")
                    + "最优先的是必修课（必须逐门通过）。");
        } else if (!blocked.isEmpty()) {
            out.put("message", "培养方案里你还差 " + gapType.size() + " 门课，但这些课现在都不能选："
                    + (firstBlockReason == null ? "当前不在选课开放时间内。" : firstBlockReason));
        } else {
            out.put("message", "培养方案里你还差 " + gapType.size() + " 门课（"
                    + String.join("、", gapType.keySet()) + "），但它们目前没有开课，暂时没有可选的。");
        }
        return out;
    }

    private Map<String, Object> summarizeAudit(CreditService.AuditResult audit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planFound", audit.planFound());
        m.put("planName", audit.planName());
        m.put("earnedCredits", audit.earnedCredits());
        m.put("totalCredits", audit.totalCredits());
        m.put("creditGap", audit.creditGap());
        m.put("satisfied", audit.planFound() && audit.creditSatisfied()
                && audit.requiredSatisfied() && audit.electiveSatisfied());
        m.put("requiredSatisfied", audit.requiredSatisfied());
        m.put("electiveSatisfied", audit.electiveSatisfied());
        return m;
    }

    private Map<String, Object> planMap(TrainingPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", plan.getId());
        m.put("planName", plan.getPlanName());
        m.put("grade", plan.getGrade());
        m.put("majorId", plan.getMajorId());
        m.put("majorName", plan.getMajorName());
        m.put("collegeName", plan.getCollegeName());
        m.put("totalCredits", plan.getTotalCredits());
        m.put("requiredCredits", plan.getRequiredCredits());
        m.put("electiveCredits", plan.getElectiveCredits());
        m.put("status", plan.getStatus());
        m.put("remark", plan.getRemark());
        return m;
    }

    private Map<String, Object> placementMap(Student placement) {
        if (placement == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentName", placement.getStudentName());
        m.put("grade", placement.getGrade());
        m.put("majorId", placement.getMajorId());
        m.put("majorName", placement.getMajorName());
        m.put("collegeName", placement.getCollegeName());
        m.put("clazzName", placement.getClazzName());
        return m;
    }

    private static String placementDesc(Student placement) {
        if (placement == null) {
            return "未找到你的学籍信息";
        }
        String major = placement.getMajorName() == null ? "专业信息缺失" : placement.getMajorName();
        String grade = placement.getGrade() == null ? "年级信息缺失" : placement.getGrade() + " 级";
        return grade + " " + major;
    }

    private Map<String, Object> classTimeMap(ClassTime ct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("classTimeId", ct.getId());
        m.put("courseId", ct.getCourseId());
        m.put("courseCode", ct.getCourseCode());
        m.put("courseName", ct.getCourseName());
        m.put("teacherName", ct.getTeacherName());
        m.put("weekday", ct.getWeekday());
        m.put("weekdayText", weekdayText(ct.getWeekday()));
        m.put("startPeriod", ct.getStartPeriod());
        m.put("endPeriod", ct.getEndPeriod());
        m.put("periodText", periodText(ct.getStartPeriod(), ct.getEndPeriod()));
        m.put("startWeek", ct.getStartWeek());
        m.put("endWeek", ct.getEndWeek());
        m.put("weekText", weekText(ct.getStartWeek(), ct.getEndWeek()));
        m.put("roomName", ct.getRoomName());
        m.put("roomText", ct.getRoomName() == null ? "教室待定" : ct.getRoomName());
        m.put("term", ct.getTerm());
        return m;
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    /**
     * 按 {@code courseId} / {@code courseCode} / {@code courseName} 三者之一定位课程。
     *
     * <p><b>为什么要有这个方法</b>：{@code check_time_conflict} 最初只收 {@code courseId}，
     * 而用户永远只会说课程名（"数据结构与算法这门课和我课表冲突吗"）。实测 7B 模型既没有先去
     * {@code get_course_list} 查 id，也没有报错，而是**编了一个 courseId=101**——工具返回
     * "课程不存在"，用户什么也没得到。让工具接受用户实际会说的东西，比在提示词里反复叮嘱
     * "记得先查 id" 可靠得多。
     *
     * <p>命中多条（同一代码/名称有多个学期）时取**最近学期**（SQL 已按 term 倒序），
     * 并把候选列表放进 {@code meta} 一并回给模型，避免"安静地查了另一个学期"。
     */
    private record CourseResolution(Course course, Map<String, Object> error, Map<String, Object> meta) {
        static CourseResolution of(Course course, Map<String, Object> meta) {
            return new CourseResolution(course, null, meta);
        }

        static CourseResolution failed(String message) {
            return new CourseResolution(null, Map.of("error", message), Map.of());
        }
    }

    private CourseResolution resolveCourse(Map<String, Object> args) {
        Integer courseId = integer(args, "courseId");
        String courseCode = str(args, "courseCode");
        String courseName = str(args, "courseName");

        if (courseId != null) {
            Course course = courseMapper.selectCourseById(courseId);
            if (course == null) {
                return CourseResolution.failed("课程不存在（courseId=" + courseId + "）。"
                        + "可以改用 courseCode（如 CS101）或 courseName，或先用 get_course_list 查课程列表。");
            }
            return CourseResolution.of(course, Map.of());
        }

        String matchedBy;
        List<Course> candidates;
        if (courseCode != null && !courseCode.isBlank()) {
            matchedBy = "courseCode";
            candidates = courseMapper.selectByCode(courseCode.trim());
        } else if (courseName != null && !courseName.isBlank()) {
            matchedBy = "courseName";
            candidates = courseMapper.selectCourseByName(courseName.trim());
        } else {
            return CourseResolution.failed("请给出要检查的课程：courseCode（如 CS101）、courseName 或 courseId，三者任选其一。");
        }

        if (candidates == null || candidates.isEmpty()) {
            String value = "courseCode".equals(matchedBy) ? courseCode : courseName;
            return CourseResolution.failed("未找到课程（" + matchedBy + "=" + value + "）。"
                    + "可以用 get_course_list 查看可选课程列表，或确认课程名/代码是否正确。");
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        if (candidates.size() > 1) {
            List<Map<String, Object>> others = new ArrayList<>();
            for (Course c : candidates) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("courseId", c.getId());
                item.put("term", String.valueOf(c.getTerm()));
                others.add(item);
            }
            meta.put("candidateCount", candidates.size());
            meta.put("candidates", others);
            meta.put("matchedBy", matchedBy);
            meta.put("note", "同一" + ("courseCode".equals(matchedBy) ? "课程代码" : "课程名称")
                    + "有多个学期的开课记录，已按最近学期检查；如需其它学期请用 courseId 指定。");
        }
        return CourseResolution.of(candidates.get(0), meta);
    }

    /**
     * 统一的执行包装：业务异常 → 中文原因；其它异常 → 通用文案（不外泄 SQL/表名/堆栈）。
     *
     * <p>返回 {@code {"error": ...}} 而不是抛出：抛出去会被上层换成通用失败载荷，
     * 模型就拿不到可解释的信息，只能对用户说"系统错误"。
     */
    private String json(ToolBody body) {
        try {
            return objectMapper.writeValueAsString(body.run());
        } catch (BusinessException e) {
            return errorJson(e.getMessage());
        } catch (Exception e) {
            log.warn("学生工具执行失败: {}", e.toString());
            return errorJson("查询暂时不可用，请稍后再试");
        }
    }

    private String errorJson(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message == null || message.isBlank() ? "操作未能完成" : message);
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"error\":\"操作未能完成\"}";
        }
    }

    @FunctionalInterface
    private interface ToolBody {
        Object run() throws Exception;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean bool(Map<String, Object> args, String key, boolean defaultValue) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return defaultValue;
        }
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s);
    }

    /** 模型有时把整数写成字符串，这里两种都收；非法值返回 null 由调用方给友好提示 */
    private static Integer integer(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String weekdayText(Integer weekday) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            return "星期未知";
        }
        return WEEKDAY_CN[weekday - 1];
    }

    private static String periodText(Integer start, Integer end) {
        if (start == null || end == null) {
            return "节次未知";
        }
        return start.equals(end) ? "第" + start + "节" : "第" + start + "-" + end + "节";
    }

    private static String weekText(Integer start, Integer end) {
        if (start == null || end == null) {
            return "周次未知";
        }
        return "第" + start + "-" + end + "周";
    }

    /** 一节排课的完整中文描述，用于冲突提示 */
    private static String slotText(ClassTime ct) {
        return weekdayText(ct.getWeekday()) + " " + periodText(ct.getStartPeriod(), ct.getEndPeriod())
                + " " + weekText(ct.getStartWeek(), ct.getEndWeek())
                + (ct.getRoomName() == null ? "" : " " + ct.getRoomName());
    }

    private static String joinConflicts(List<ClassTime> conflicts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conflicts.size(); i++) {
            ClassTime ct = conflicts.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append('《').append(ct.getCourseName() == null ? ct.getCourseCode() : ct.getCourseName())
              .append('》').append(slotText(ct));
        }
        return sb.toString();
    }

    /** 建议学期序号 → "大一上" */
    private static String semesterText(Integer semester) {
        if (semester == null || semester < 1) {
            return "未指定";
        }
        int grade = (semester - 1) / 2 + 1;
        String half = (semester - 1) % 2 == 0 ? "上" : "下";
        String[] names = {"一", "二", "三", "四", "五"};
        String g = grade >= 1 && grade <= names.length ? names[grade - 1] : String.valueOf(grade);
        return "大" + g + half;
    }

    /** 选课状态翻译成一句学生能照做的话 */
    private static String selectionAdvice(SelectionRoundService.SelectionStatus st) {
        if (st.canSelect() && st.canDrop()) {
            return "现在可以选课，也可以退课。";
        }
        if (st.canSelect()) {
            return "现在可以选课。";
        }
        if (st.canDrop()) {
            return "当前处于补退选期间：只能退课，不能再选课。";
        }
        if (!st.roundOpen()) {
            return "选课未开放：现在只能查看课程，不能选也不能退。";
        }
        return "当前不在选课或补退选时间窗内，暂时不能选课也不能退课。";
    }

    private static String safeName(Course course) {
        if (course.getCourseName() != null && !course.getCourseName().isBlank()) {
            return course.getCourseName();
        }
        return course.getCourseCode() == null ? ("课程#" + course.getId()) : course.getCourseCode();
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME);
    }

    /** BigDecimal 去掉无意义的尾随 0，避免对学生说"缺口 25.500 学分" */
    private static String plain(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private Map<String, Object> noParams() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
