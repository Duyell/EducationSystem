package duyell.ai.tool.declarative;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.mapper.ClassTimeMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.mapper.EvaluationMapper;
import duyell.mapper.ScoreMapper;
import duyell.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import utils.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 学生工具的**声明式实现**（M2 计划 1.3 批量迁移）。
 *
 * <p><b>本类与手写版的关系</b>：{@code StudentToolRegistrar} 里的手写实现**原样保留**作回归对照，
 * 由 {@code DeclarativeToolRegistrar} 在启动完成后用本类的声明式版本覆盖注册
 * （工具名、展示名、风险等级、参数 Schema 形状三者必须与手写版一致）。
 * 因此这里的每个方法都是"手写版执行体 + 声明式签名"，业务逻辑仍然落在同一批 service/mapper 上。
 *
 * <p><b>三条写代码时必须守住的规矩</b>：
 * <ol>
 *   <li><b>危险等级绝不能标错</b>：{@code DANGEROUS} 才会弹确认卡片（选课、评教），
 *       标成只读就等于让写操作直接执行；</li>
 *   <li><b>参数用平铺的 {@code @ToolParam} 形参</b>，不要用单个 DTO record：
 *       框架对"单个对象参数"会**再套一层参数名**（{@code {"request":{"courseId":...}}}），
 *       那是一次静默的契约破坏（见 {@code docs/开发记录.md} (二十一) 坑 ②）；</li>
 *   <li><b>调用者身份只经 {@link ToolContext}</b>（{@link DeclarativeToolContext#currentUserId}），
 *       绝不进参数 Schema——进了模型就能改成别人的学号。</li>
 * </ol>
 *
 * <p><b>为什么这里有一份"组装代码"的副本</b>：手写版把"服务层 record → 给模型看的 JSON"
 * 的组装写成了一批私有方法（{@code auditMap} / {@code classTimeMap} / {@code planMap} …），
 * 而任务边界要求手写 Registrar 一行不改（它就是回归对照）。
 * 于是本类照抄了这些组装逻辑：<b>业务事实来自同一个 service</b>，
 * 重复的只是"字段怎么摆"。这份重复由 {@code StudentDeclarativeToolsTest} 钉住
 * （逐个工具断言 displayName/riskLevel/required/properties 形状 + 关键业务数据），
 * 一旦手写版改了字段名而这里没跟上，测试会红。
 *
 * <p>⚠️ 参数的**取值约束**（如评分的 {@code minimum/maximum}）框架表达不了，
 * 用项目侧的 {@link ParamConstraint} 补在形参上，由 {@code DeclarativeToolScanner} 合并进 Schema——
 * 迁移因此**不降低**任何既有约束（模型看到的与校验器执行的都是同一份）。
 *
 * @author duyell
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudentDeclarativeTools implements DeclarativeToolGroup {

    private final CourseSelectionMapper courseSelectionMapper;
    private final CourseMapper courseMapper;
    private final CourseSelectionService courseSelectionService;
    private final ObjectMapper objectMapper;

    // ---------- 与手写 Registrar 相同的那批服务/映射器（业务逻辑的唯一来源） ----------
    private final ScoreMapper scoreMapper;
    private final EvaluationMapper evaluationMapper;
    private final TrainingPlanService trainingPlanService;
    private final CreditService creditService;
    private final GpaService gpaService;
    private final ExamService examService;
    private final SelectionRoundService selectionRoundService;
    private final ScheduleService scheduleService;
    private final AcademicWarningService academicWarningService;
    private final EvaluationService evaluationService;
    private final ClassTimeMapper classTimeMapper;

    private static final String[] WEEKDAY_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String role() {
        return "student";
    }

    // ==================================================================================
    // 1. 只读：我的数据（成绩 / 评价 / 培养方案 / 学分审核 / 绩点 / 预警）
    // ==================================================================================

    @Tool(name = "get_my_scores",
            description = "查询**当前登录学生本人**的全部成绩（含课程代码、课程名、平时/考试/总评、是否通过、学分）。"
                    + "学生问\"我的成绩\"\"我这门课考了多少分\"\"我有没有挂科\"时使用。"
                    + "不需要任何参数，只返回本人数据；成绩只读，任何人都不能通过这里修改。")
    @ToolMeta(displayName = "我的成绩", riskLevel = RiskLevel.READ_ONLY)
    public String getMyScores(ToolContext context) throws Exception {
        String studentId = DeclarativeToolContext.currentUserId(context);
        // 与手写版一致：不过 json() 包装（ScoreMapper.list 的第二参就是 Integer 学号）
        List<Score> scores = scoreMapper.list(null, Integer.valueOf(studentId), null);
        return objectMapper.writeValueAsString(scores);
    }

    @Tool(name = "get_my_evaluations",
            description = "查询**当前登录学生本人**已提交的全部教学评价（课程、评分、评价内容、时间）。"
                    + "学生问\"我评价过哪些课\"\"我的评价写了什么\"时使用。不需要任何参数。"
                    + "如果要判断**某门课**是否已经评价过，请改用 check_evaluation。")
    @ToolMeta(displayName = "我的评价", riskLevel = RiskLevel.READ_ONLY)
    public String getMyEvaluations(ToolContext context) throws Exception {
        String studentId = DeclarativeToolContext.currentUserId(context);
        List<TeacherEvaluation> evaluations = evaluationMapper.list(null, studentId, null);
        return objectMapper.writeValueAsString(evaluations);
    }

    @Tool(name = "get_my_training_plan",
            description = "查看**本人适用**的培养方案（专业 + 入学年级推导）及其全部课程明细、必修/选修构成。"
                    + "学生问「我的培养方案是什么」「我要修哪些课」「大一上该上什么」时使用。"
                    + "本工具**没有参数**，直接调用即可，不要追问专业或年级——系统按学籍自动定位。"
                    + "若该专业年级尚未录入方案，返回里 plan 为 null 且 message 说明原因，请照实告知用户。")
    @ToolMeta(displayName = "我的培养方案", riskLevel = RiskLevel.READ_ONLY)
    public String getMyTrainingPlan(ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("studentId", studentId);

            Student placement = trainingPlanService.getStudentPlacement(studentId);
            out.put("placement", placementMap(placement));

            TrainingPlan plan = trainingPlanService.resolvePlanForStudent(studentId);
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
        });
    }

    @Tool(name = "audit_my_graduation",
            description = "按培养方案核对**本人**的毕业学分完成情况：已修学分、必修是否逐门通过、选修学分是否达标、"
                    + "总学分缺口，以及还没通过的必修课清单。"
                    + "学生问「我学分够毕业吗」「我还差多少学分」「我还有哪门必修没过」时使用。"
                    + "本工具**没有参数**，直接调用即可。"
                    + "返回里的 satisfied/creditGap 是服务端算好的派生值（record 派生方法不会进 JSON，此处已手工补上）。")
    @ToolMeta(displayName = "毕业学分审核", riskLevel = RiskLevel.READ_ONLY)
    public String auditMyGraduation(ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> auditMap(studentId));
    }

    @Tool(name = "get_my_gpa",
            description = "查看**本人**的平均学分绩点、逐门课程绩点明细，以及本人在**同专业同年级**内的排名。"
                    + "学生问「我的绩点是多少」「我绩点多高」「我在专业里排第几」「这门课算多少绩点」时使用。"
                    + "term 省略时返回**全部学期的累计**绩点；只有用户明确说了某个学期（如「这学期」「2024-2025-1」）"
                    + "才传 term，不要为了「确认学期」而反问。排名只返回本人名次，不含他人明细。")
    @ToolMeta(displayName = "我的绩点与排名", riskLevel = RiskLevel.READ_ONLY)
    public String getMyGpa(
            @ToolParam(description = "学期，如 2024-2025-1；**省略则返回全部学期的累计绩点**", required = false)
            String term,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("studentId", studentId);
            out.put("term", term == null ? "ALL" : term);
            out.put("termText", term == null ? "全部学期（累计）" : term);

            GpaService.GpaResult gpa = gpaService.calcGpa(studentId, term);
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

            GpaService.RankResult rank = gpaService.rankInMajor(studentId);
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
        });
    }

    @Tool(name = "get_my_academic_warning",
            description = "查询本人学业预警状态：**未通过课程**（考核不及格且补考未通过）的**学分累计**是否达到阈值，"
                    + "以及未通过课程清单。学生问「我有没有学业预警」「我挂了几门」「多少学分没过」时使用。"
                    + "无参数，只读。预警**只是提示**，不会自动产生留级、退学等任何处理；"
                    + "严重情况的处理由教务人工决定。")
    @ToolMeta(displayName = "我的学业预警", riskLevel = RiskLevel.READ_ONLY)
    public String getMyAcademicWarning(ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> {
            AcademicWarningService.WarningStatus st = academicWarningService.statusFor(studentId);
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
        });
    }

    // ==================================================================================
    // 2. 只读：课程与选课（可选课程 / 推荐 / 开放状态 / 课表 / 冲突 / 考试）
    // ==================================================================================

    @Tool(name = "get_course_list",
            description = "查看可选课程列表。不传 courseName 时返回全部可选课程；"
                    + "用户说\"有哪些课能选\"\"列出所有可选课程\"时，直接调用本工具且不要追问课程名称。"
                    + "若要查的是**本人已选**的课，请改用 get_my_courses。")
    @ToolMeta(displayName = "可选课程列表", riskLevel = RiskLevel.READ_ONLY)
    public String getCourseList(
            @ToolParam(description = "课程名称关键词，用于筛选；省略则返回全部", required = false)
            String courseName) throws Exception {
        // 只读的公共课表，不涉及"谁在问"，因此**不声明 ToolContext**（与手写版一致）
        List<Course> courses = courseMapper.list(courseName, null, null, null, null, null, null);
        return objectMapper.writeValueAsString(courses);
    }

    @Tool(name = "recommend_courses",
            description = "根据**培养方案缺口**推荐现在可以选的课：先算毕业审核，再看这些缺口课程里哪些当前可选，"
                    + "并说明每门推荐它的理由；暂时不能选的也会说明被哪一条选课规则挡住。"
                    + "学生问「我该选什么课」「推荐几门课」「我还差哪些课」时使用。"
                    + "term 参数省略时**自动覆盖所有已配置选课轮次的学期**（即「现在能选什么」），"
                    + "不要为了「确认学期」而反问；只有用户明确指定学期时才传 term。"
                    + "若没有培养方案、没有缺口、或轮次未开放导致什么都选不了，返回里的 message 会说明原因，请照实转述。")
    @ToolMeta(displayName = "推荐可选课程", riskLevel = RiskLevel.READ_ONLY)
    public String recommendCourses(
            @ToolParam(description = "学期，如 2024-2025-1；**省略则覆盖所有已配置轮次的学期**", required = false)
            String term,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> recommendCourses(term, studentId));
    }

    @Tool(name = "get_selection_status",
            description = "查询**本人**现在能不能选课/退课，以及原因；判定同时考虑管理员开关、适用范围和选课/补退选时间窗。"
                    + "学生问「现在能选课吗」「选课开始了吗」「还能退课吗」「什么时候能选」时使用。"
                    + "term 省略时**返回所有已配置轮次的学期的状态**（这样不用先反问用户是哪学期）；"
                    + "只有用户明确说了某个学期才传 term。reason 是可以直接转述给用户的中文说明。")
    @ToolMeta(displayName = "选课开放状态", riskLevel = RiskLevel.READ_ONLY)
    public String getSelectionStatus(
            @ToolParam(description = "学期，如 2024-2025-1；**省略则返回所有已配置轮次的学期**", required = false)
            String term,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> {
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
                SelectionRoundService.SelectionStatus st = selectionRoundService.statusFor(studentId, t);
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
            out.put("studentId", studentId);
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
        });
    }

    @Tool(name = "list_my_class_times",
            description = "查看**本人已选课程**的上课时间（星期几、第几节、第几周、教室）。"
                    + "学生问「我的课表是什么」「我周一有什么课」「这门课什么时候上」时使用。"
                    + "term 省略时返回**全部学期**已选课程的课表；只有用户明确说了学期才传 term。"
                    + "已选但还没排课的课程也会出现在结果里（slots 为空并有提示），不会凭空消失。")
    @ToolMeta(displayName = "我的课表", riskLevel = RiskLevel.READ_ONLY)
    public String listMyClassTimes(
            @ToolParam(description = "学期，如 2024-2025-1；**省略则返回全部学期**", required = false)
            String term,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> {
            List<Course> myCourses = courseSelectionService.listMyCourses(studentId);

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
            out.put("studentId", studentId);
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
        });
    }

    @Tool(name = "get_my_exams",
            description = "查看**本人已选课程**的考试安排（考试时间、时长、考场、座位号段）。"
                    + "学生问「我下周有什么考试」「考试安排出来了吗」「某门课什么时候考」时使用。"
                    + "term 省略时返回**全部学期**的考试；upcoming 省略时默认 false（含已考完的），"
                    + "学生问「接下来/下周/还有哪些考试」时应传 upcoming=true 只看未开考的。"
                    + "只返回本人已选课程的考试，不会包含没选的课。")
    @ToolMeta(displayName = "我的考试安排", riskLevel = RiskLevel.READ_ONLY)
    public String getMyExams(
            @ToolParam(description = "学期，如 2024-2025-1；**省略则返回全部学期**", required = false)
            String term,
            @ToolParam(description = "true 只看还没开考的；**省略默认为 false**（含已考完的）", required = false)
            Boolean upcoming,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        boolean upcomingOnly = Boolean.TRUE.equals(upcoming);
        return json(() -> {
            List<ExamSchedule> exams = examService.listByStudent(studentId, term, upcomingOnly);

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
            out.put("studentId", studentId);
            out.put("term", term == null ? "ALL" : term);
            out.put("upcomingOnly", upcomingOnly);
            out.put("count", rows.size());
            out.put("exams", rows);
            if (rows.isEmpty()) {
                out.put("message", upcomingOnly
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
        });
    }

    @Tool(name = "check_time_conflict",
            description = "检查**某门课**与本人现有课表是否有上课时间冲突（同一学期、同一星期、节次与周次区间重叠）。"
                    + "学生问「这门课和我的课冲突吗」「我能选这门课吗（时间上）」「帮我查下会不会撞课」时使用。"
                    + "课程用 courseId / courseCode / courseName **任选其一**指定（课程代码如 CS101 最稳）；"
                    + "三者都不传时返回错误提示，不会瞎猜课程。"
                    + "⚠️ 用户通常只会说课程名，此时直接传 courseName，**不要**自己编一个 courseId；"
                    + "同一课程代码有多个学期时会取最近学期，返回里会回显实际检查的学期与候选列表。"
                    + "⚠️ 判据是**闭区间**：第 3-4 节与第 4-5 节共用第 4 节，算冲突；"
                    + "这与考试时间判据（半开区间，11:00 结束 vs 11:00 开始不算冲突）**不同**，不要混用。"
                    + "若该课程尚未排课，则不可能冲突，返回里会明确说明。")
    @ToolMeta(displayName = "检查上课时间冲突", riskLevel = RiskLevel.READ_ONLY)
    public String checkTimeConflict(
            @ToolParam(description = "课程ID（与 courseCode/courseName 任选其一；已知 ID 时优先用它）", required = false)
            Integer courseId,
            @ToolParam(description = "课程代码，如 CS101（与 courseId/courseName 任选其一）", required = false)
            String courseCode,
            @ToolParam(description = "课程名称，用户只说课名时用它（与 courseId/courseCode 任选其一）", required = false)
            String courseName,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        return json(() -> {
            CourseResolution resolved = resolveCourse(courseId, courseCode, courseName);
            if (resolved.error() != null) {
                return resolved.error();
            }
            Course course = resolved.course();
            Integer resolvedCourseId = course.getId();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("courseId", resolvedCourseId);
            out.put("courseCode", course.getCourseCode());
            out.put("courseName", course.getCourseName());
            out.put("term", course.getTerm());
            out.putAll(resolved.meta());
            if (course.getTeacherName() != null) {
                out.put("teacherName", course.getTeacherName());
            }

            List<ClassTime> selfSlots = scheduleService.listByCourse(resolvedCourseId);
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

            List<ClassTime> conflicts = classTimeMapper.selectStudentConflicts(studentId, resolvedCourseId);
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
        });
    }

    @Tool(name = "check_evaluation",
            description = "检查**某门课程**是否已经评价过（返回 evaluated 布尔值与中文说明）。"
                    + "学生问「这门课我评价了吗」「还有哪门课没评价」时使用。"
                    + "需要课程ID；如果只知道课程名，先用 get_my_courses 或 get_course_list 拿到 courseId。")
    @ToolMeta(displayName = "评价状态检查", riskLevel = RiskLevel.READ_ONLY)
    public String checkEvaluation(
            @ToolParam(description = "课程ID", required = true)
            Integer courseId,
            ToolContext context) throws Exception {
        String studentId = DeclarativeToolContext.currentUserId(context);
        // 与手写版一致：不过 json() 包装，固定文案直接返回
        TeacherEvaluation existing = evaluationMapper.selectByCourseAndStudent(courseId, studentId);
        if (existing != null) {
            return "{\"evaluated\":true,\"message\":\"该课程已评价\"}";
        }
        return "{\"evaluated\":false,\"message\":\"该课程尚未评价\"}";
    }

    // ==================================================================================
    // 3. 写操作（退课 WRITE / 评教 DANGEROUS）
    // ==================================================================================

    @Tool(name = "drop_course",
            description = "为**当前登录学生本人**退掉一门已选课程（课程从已选列表移除；之后可以通过重新选课恢复）。"
                    + "学生明确说\"我要退课\"\"帮我把XX课退了\"且已经知道课程ID时，**直接调用本工具**。"
                    + "退课是否允许由选课时间窗决定：如果当前处于补退选期间只能退课、不能选课；"
                    + "不在时间窗内会被服务端拒绝，请把返回里的中文原因如实转述给用户。")
    @ToolMeta(displayName = "退课", riskLevel = RiskLevel.WRITE)
    public String dropCourse(
            @ToolParam(description = "课程ID（要退掉的课程）", required = true)
            Integer courseId,
            ToolContext context) throws Exception {
        String studentId = DeclarativeToolContext.currentUserId(context);
        // 与手写版逐字一致：直接删选课记录并回固定文案（不额外包 try/catch，保持错误路径不变）
        courseSelectionMapper.delete(courseId, studentId);
        return "{\"message\":\"退课成功\"}";
    }

    /**
     * 教学评价（危险操作，执行前会弹确认卡片）。
     *
     * <p><b>评分 1~5 的边界</b>：手写版的 Schema 里写了 {@code minimum: 1, maximum: 5}，
     * 而本项目的 {@code JsonSchemaToolArgumentValidator} **真的会按它拦截**
     * （超范围 → {@code INVALID_ARGUMENTS}、工具不执行）。框架的 {@code @ToolParam}
     * 表达不了数值上下界，因此用项目侧的 {@link ParamConstraint} 补上——
     * 由 {@code DeclarativeToolScanner} 合并进框架生成的 Schema，
     * 于是"模型看到的约束"与"校验器执行的约束"与手写版**完全一致**（迁移不降低约束）。
     *
     * <p>方法体内**仍然**再挡一次（越界直接返回可读原因）：那是给"绕过参数校验器直接执行工具"
     * 的路径兜底的（例如 `ToolRegistry` 被直接调用、或将来有人改了编排顺序）。
     * 两层的关系是"Schema 约束＝主闸门，方法体校验＝兜底"，不是二选一。
     */
    @Tool(name = "evaluate_teacher",
            description = "对**某门课程**的教师进行教学评价（**5 星制，score 只能是 1~5 的整数**；提交后不可修改）。"
                    + "学生说\"我要评价XX课\"\"给XX老师打分\"时，**直接调用本工具**："
                    + "写入前的确认由系统弹出的确认卡片负责，你不需要在文字里再问一次\"是否确认\"。"
                    + "content 省略时只提交评分、不附文字评价。"
                    + "只能评价**本人已选**的课程；教师由课程决定，不要向用户索要教师工号以外的信息，"
                    + "也不要在用户没说的情况下自己编一个 teacherId——返回里的错误原因会说明问题。")
    @ToolMeta(displayName = "教学评价", riskLevel = RiskLevel.DANGEROUS)
    public String evaluateTeacher(
            @ToolParam(description = "课程ID", required = true)
            Integer courseId,
            @ToolParam(description = "教师工号", required = true)
            String teacherId,
            @ParamConstraint(min = 1, max = 5)
            @ToolParam(description = "评分（5 星制，1~5 的整数）", required = true)
            Integer score,
            @ToolParam(description = "评价内容；省略则不附文字评价", required = false)
            String content,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        if (score == null || score < 1 || score > 5) {
            // 兜底：正常路径上参数校验器已经拦住越界值（见方法注释），这里防止绕过校验器直接执行
            return "{\"error\":\"评分必须是 1~5 的整数（5 星制）\"}";
        }
        TeacherEvaluation eval = new TeacherEvaluation();
        eval.setCourseId(courseId);
        eval.setStudentId(studentId);
        eval.setTeacherId(teacherId);
        eval.setScore(score);
        eval.setContent(content == null ? "" : content);
        // ⚠️ 必须走 service：它负责"只能评价本人已选课程"「评价对象由课程决定」「不能重复评价」
        // 三条服务端校验（直接 evaluationMapper.add 等于绕过全部约束）
        evaluationService.add(eval);
        return "{\"message\":\"评价提交成功\"}";
    }

    // ==================================================================================
    // 组装辅助（与手写 Registrar 的同名私有方法保持同一份字段结构）
    // ==================================================================================

    /** 培养方案 → 给模型看的扁平对象 */
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

    /** 学籍定位信息（专业/年级/班级），null 时原样回 null */
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

    /** 毕业审核：手工摊平 + 补上 record 派生方法（satisfied / creditGap 不进 JSON） */
    private Map<String, Object> auditMap(String studentId) {
        CreditService.AuditResult audit = creditService.auditGraduation(studentId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
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
    private Map<String, Object> recommendCourses(String term, String studentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);

        CreditService.AuditResult audit = creditService.auditGraduation(studentId);
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
            for (CourseSelectionService.SelectableCourse sc
                    : courseSelectionService.listSelectableCourses(studentId, t)) {
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
                item.put("gapTypeText", "REQUIRED".equals(gapType.get(c.getCourseCode()))
                        ? "必修（必须通过）" : "选修（凑学分）");

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

    /**
     * 按 {@code courseId} / {@code courseCode} / {@code courseName} 三者之一定位课程。
     *
     * <p>{@code check_time_conflict} 最初只收 {@code courseId}，而用户永远只会说课程名，
     * 实测 7B 模型既不先查 id 也不报错，而是**编了一个 courseId=101**。
     * 让工具接受用户实际会说的东西，比在提示词里反复叮嘱"记得先查 id"可靠得多。
     * 命中多条（同一代码/名称有多个学期）时取**最近学期**（SQL 已按 term 倒序），
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

    private CourseResolution resolveCourse(Integer courseId, String courseCode, String courseName) {
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

    // ==================================================================================
    // 统一执行包装与格式化小工具（与手写 Registrar 同语义）
    // ==================================================================================

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
            log.warn("学生工具（声明式）执行失败: {}", e.toString());
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

    // ==================================================================================
    // 试点阶段已迁移的两个工具（get_my_courses / select_course）
    // ==================================================================================

    @Tool(name = "get_my_courses",
            description = "查询**当前登录学生本人**已选的课程列表（含课程名、授课教师、学期、学分）。"
                    + "学生问\"我选了什么课\"\"我的课表上有哪些课\"\"我这学期修了哪些课\"时使用。"
                    + "只返回本人数据；不需要任何参数。"
                    + "如果学生问的是\"有哪些课可以选\"，请改用 get_course_list。")
    @ToolMeta(displayName = "我的已选课程", riskLevel = RiskLevel.READ_ONLY)
    public String getMyCourses(ToolContext context) throws Exception {
        String studentId = DeclarativeToolContext.currentUserId(context);

        List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(studentId);
        // IN 查询一次取出全部课程，避免 N+1（与手写实现同一口径）
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

    /**
     * 选课（危险操作，执行前会弹确认卡片）。
     *
     * <p>⚠️ 参数刻意写成**平铺的 {@code @ToolParam} 形参**，而不用一个 DTO record 包起来：
     * 框架的 {@code JsonSchemaGenerator} 对"单个对象参数"会**再套一层参数名**，生成的 Schema 是
     * <pre>{"properties":{"request":{"properties":{"courseId":...},"required":["courseId"]}}}</pre>
     * 也就是模型得写成 {@code {"request":{"courseId":5}}}。而手写实现的契约是平铺的
     * {@code {"courseId":5}}——迁移如果悄悄换了模型要填的形状，就是一次**静默的契约破坏**
     * （模型会开始传错参数，而所有编译期检查都不会报错）。
     * 这个形状差异是 {@code DeclarativeToolMigrationTest} 断言 {@code properties} 里有 {@code courseId} 时抓到的。
     */
    @Tool(name = "select_course",
            description = "为**当前登录学生本人**选一门课。"
                    + "学生明确说\"我要选某门课\"\"帮我选上XX课\"且已经知道课程ID时，**直接调用本工具**："
                    + "写入前的确认由系统弹出的确认卡片负责，你不需要在文字里再问一次\"是否确认\"。"
                    + "如果学生还没确定选哪门课，请先用 get_course_list 给出可选课程，不要凭空猜 courseId。")
    @ToolMeta(displayName = "选课", riskLevel = RiskLevel.DANGEROUS)
    public String selectCourse(
            @ToolParam(description = "课程ID（来自可选课程列表里的 courseId，不是课程代码）", required = true)
            Integer courseId,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        if (courseId == null) {
            return "{\"message\":\"缺少课程ID，请先确认要选哪门课\"}";
        }
        try {
            // 复用选课服务：事务内完成查重、容量校验、时间冲突与并发控制
            courseSelectionService.select(courseId, studentId);
            return "{\"message\":\"选课成功\"}";
        } catch (RuntimeException e) {
            // 业务原因（已修过/时间冲突/名额已满/未开放…）如实转述给模型，由它解释给用户
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
