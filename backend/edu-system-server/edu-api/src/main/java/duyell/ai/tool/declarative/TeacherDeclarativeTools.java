package duyell.ai.tool.declarative;

import com.duyell.ClassTimeApply;
import com.duyell.Course;
import com.duyell.CourseApply;
import com.duyell.CourseSelection;
import com.duyell.Score;
import com.duyell.Student;
import com.duyell.TeacherEvaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.mapper.ScoreMapper;
import duyell.mapper.StudentMapper;
import duyell.service.CourseApplyService;
import duyell.service.CourseService;
import duyell.service.EvaluationService;
import duyell.service.ScheduleService;
import duyell.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import utils.BusinessException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师工具的**声明式实现**（M2 计划 1.3 批量迁移）。
 *
 * <p>本类覆盖 {@code TeacherToolRegistrar} 里的**全部**教师工具；手写实现**保留不动**
 * （计划 1.4 要求旧实现留一个版本周期作对照），由 {@code DeclarativeToolRegistrar}
 * 在启动完成后用声明式版本覆盖注册（见该类注释：用 {@code SmartInitializingSingleton}
 * 保证"声明式确定性接管手写"）。
 *
 * <p><b>迁移纪律（每一条都由测试盯着）</b>：
 * <ul>
 *   <li>工具名/展示名/风险等级与手写版**逐字一致**——风险等级错了就是 HITL 失效或误弹卡片；</li>
 *   <li>参数用**平铺的 {@code @ToolParam} 形参**（不要单个 DTO：框架会给它再套一层参数名，
 *       等于把 {@code {"courseId":5}} 悄悄变成 {@code {"request":{"courseId":5}}}，见开发记录 (二十一)）；</li>
 *   <li>调用者身份只经 {@link ToolContext} 传入（{@link DeclarativeToolContext}），
 *       **绝不**出现在参数 Schema 里，否则模型可以填别人的工号；</li>
 *   <li>执行体复用与手写版**相同的 service**：成绩一律走 {@code ScoreService}
 *       （总成绩权重/精度/passed 派生只有那一处实现，绕过它曾导致 passed 为 NULL）、
 *       评教走 {@code EvaluationService.listForTeacher}（匿名剥离只有那一处实现）；</li>
 *   <li>归属校验保留：只能操作本人所授课程，被拒时返回可读原因（与手写版同样的措辞）。</li>
 * </ul>
 *
 * <p><b>⚠️ 取值约束（enum/minimum/maximum）用 {@link ParamConstraint} 补，不能用框架注解</b>
 * <p>手写 Schema 里每个数值参数都带 {@code minimum}/{@code maximum}（成绩 0~100、星期 1~7、
 * 节次 1~10、周次 1~53…），而 Spring AI 1.0.9 的 {@code @ToolParam} 只有
 * {@code description}/{@code required}——它的 {@code JsonSchemaGenerator.generateForMethodInput}
 * 对方法参数是**手工拼装**的（只写入 type/format/description/required，
 * 对参数上的 {@code @Schema} 也仅读 description 与 requiredMode），
 * **参数级的 {@code @Schema(minimum=…)} 根本不会进入结果**（反编译核对过；
 * victools 只拿到参数的**类型**，拿不到参数这个"成员"）。
 * 因此本项目加了 {@link ParamConstraint}（标在形参上，与 {@code @ToolParam} 并存），
 * 由 {@code DeclarativeToolScanner} 在框架生成的 Schema 上**追加** enum/minimum/maximum。
 *
 * <p>本类**逐个核对**了 {@code TeacherToolRegistrar} 里的每一处 {@code minimum}/{@code maximum}：
 * <pre>
 *   enter_score   usualScore/examScore         : 0~100
 *   update_score  usualScore/examScore         : 0~100
 *   submit_course_apply credit                 : min 0
 *                       classHour              : min 0
 *                       maxStudent             : min 1
 *                       expectedWeekday        : 1~7
 *                       expectedStart/EndPeriod: 1~10
 *                       expectedStart/EndWeek  : 1~53
 *                       preferRoomId           : 无约束（手写版就没有，别擅自收紧）
 *   apply_class_time    courseId               : min 1
 *                       weekday                : 1~7
 *                       start/endPeriod        : 1~10
 *                       start/endWeek          : 1~53
 *                       roomId                 : min 1
 *   get_course_students courseId               : 无约束（与手写版一致）
 * </pre>
 * 教师工具的手写 Schema 里**没有 enum 约束**（{@code options} 是给 {@code list_users.role} 那类
 * 三选一参数用的），所以本类不出现 {@code options}。
 * 这层约束是**模型可见的提示**；硬闸门仍在服务端
 * （{@code ScoreServiceImpl.validateRange} 抛"必须在 0~100 之间"、
 * {@code ScheduleService.validateSlot} 校验星期/节次/周次）——
 * 服务里那句注释"Schema 只是提示，服务端才是硬闸门"正是这个意思。
 *
 * @author duyell
 */
@Component
@RequiredArgsConstructor
public class TeacherDeclarativeTools implements DeclarativeToolGroup {

    private final CourseMapper courseMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final ScoreMapper scoreMapper;
    private final StudentMapper studentMapper;
    private final CourseService courseService;
    private final CourseApplyService courseApplyService;
    private final ScheduleService scheduleService;
    private final ScoreService scoreService;
    private final EvaluationService evaluationService;
    private final ObjectMapper objectMapper;

    @Override
    public String role() {
        return "teacher";
    }

    // ==================================================================================
    // 1. 我的授课课程（已迁，保留原样；与学生版同名，是跨角色隔离的验证点）
    // ==================================================================================

    @Tool(name = "get_my_courses",
            description = "查询**当前登录教师本人**所教授的课程列表（含课程代码、课程名、学期、学分、上课人数上限）。"
                    + "教师问\"我教哪些课\"\"我的授课课程\"时使用；不需要任何参数。"
                    + "注意：本工具只返回该教师自己的课程，教师无权查看他人课程；"
                    + "学生视角的已选课程请由学生账号查询。")
    @ToolMeta(displayName = "我的授课课程", riskLevel = RiskLevel.READ_ONLY)
    public String getMyCourses(ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);
        List<Course> courses = courseMapper.selectByTeacherId(teacherId);
        // 与手写实现保持同一返回结构（Course 全字段）：模型据此回答课程/学期/学分等问题
        return objectMapper.writeValueAsString(courses);
    }

    // ==================================================================================
    // 2. 课程学生名单（只读）
    // ==================================================================================

    @Tool(name = "get_course_students",
            description = "查看**本人所授**某门课程的选课学生名单（含学号、姓名、班级/学院等）。"
                    + "教师问\"这门课有哪些学生\"\"我课上有谁\"\"给我看看选课名单\"时使用。"
                    + "只能查本人授课的课程：查他人课程会被拒绝并说明原因，此时请如实转述、不要重试。"
                    + "课程ID可用 get_my_courses 查到；返回空数组表示该课程确实还没有人选。")
    @ToolMeta(displayName = "课程学生名单", riskLevel = RiskLevel.READ_ONLY)
    public String getCourseStudents(
            @ToolParam(description = "课程ID（必须是本人授课的课程，可用 get_my_courses 查）", required = true)
            Integer courseId,
            ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);
        // 归属校验：教师仅能查看自己课程的选课名单
        if (!courseService.isCourseOfTeacher(teacherId, courseId)) {
            return errorJson("无权限查看该课程的选课名单");
        }
        List<CourseSelection> selections = courseSelectionMapper.selectByCourseId(courseId);
        List<String> studentIds = selections.stream()
                .map(CourseSelection::getStudentId)
                .toList();
        if (studentIds.isEmpty()) {
            return "[]";
        }
        List<Student> students = studentMapper.selectByStudentIds(studentIds);
        return objectMapper.writeValueAsString(students);
    }

    // ==================================================================================
    // 3. 录入成绩（危险操作，弹确认卡片）
    // ==================================================================================

    @Tool(name = "enter_score",
            description = "为**本人所授课程**的学生录入成绩（平时成绩 + 考试成绩，"
                    + "总成绩 = 平时成绩 × 0.4 + 考试成绩 × 0.6）。成绩为**百分制 0~100**。"
                    + "教师说\"给这个学生录成绩\"\"这门课的成绩记一下\"时**直接调用本工具**："
                    + "写入前的确认由系统弹出的确认卡片负责，你不需要在文字里再问一次\"是否确认\"。"
                    + "usualScore / examScore 都是可选的：**省略即按 0 分参与计算**，"
                    + "不要为了这两个可选参数反问教师；只有课程ID或学号缺失时才需要问。"
                    + "只能为本人授课的课程录入；若该生此课程已有成绩，会提示改用 update_score，不要重复录入。")
    @ToolMeta(displayName = "录入成绩", riskLevel = RiskLevel.DANGEROUS)
    public String enterScore(
            @ToolParam(description = "课程ID（必须是本人授课的课程，可用 get_my_courses 查）", required = true)
            Integer courseId,
            @ToolParam(description = "学生学号（该课程选课名单里的学号，纯数字）", required = true)
            String studentId,
            @ToolParam(description = "平时成绩（0~100，可选；省略则按 0 分计）", required = false)
            @ParamConstraint(min = 0, max = 100) Double usualScore,
            @ToolParam(description = "考试成绩（0~100，可选；省略则按 0 分计）", required = false)
            @ParamConstraint(min = 0, max = 100) Double examScore,
            ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);

        // 归属校验：教师仅能为自己课程的选课学生录入成绩
        if (!courseService.isCourseOfTeacher(teacherId, courseId)) {
            return errorJson("无权限为该课程录入成绩");
        }
        Integer studentNo = parseStudentId(studentId);
        if (studentNo == null) {
            return errorJson("学号必须是纯数字，实际为 " + studentId);
        }
        // 已有成绩：与手写版同样的提示（改成绩请走 update_score）
        if (scoreMapper.select(courseId, studentNo) != null) {
            return messageJson("该学生此课程已有成绩记录，请使用 update_score 修改");
        }

        Score score = new Score();
        score.setCourseId(courseId);
        score.setStudentId(studentId);
        score.setUsualScore(toDecimal(usualScore));
        score.setExamScore(toDecimal(examScore));
        try {
            // ⚠️ 必须交给 ScoreService，而不是直接 scoreMapper.add：
            // 总成绩权重、精度（3 位小数）与 passed 派生字段都只有那一处实现。
            // 曾经这里自己算总分且不写 passed，结果是：① 同一份成绩走助手与走界面得到不同总分；
            // ② passed 为 NULL 使「已修过」选课校验漏判，学生录完成绩还能重复选同一门课。
            scoreService.add(score);
        } catch (RuntimeException e) {
            // 例如成绩越界：服务端是硬闸门（validateRange），把可读原因回灌给模型，由它向教师解释
            return errorJson(e.getMessage());
        }
        return json("message", "成绩录入成功", "totalScore", score.getTotalScore());
    }

    // ==================================================================================
    // 4. 修改成绩（危险操作，弹确认卡片）
    // ==================================================================================

    @Tool(name = "update_score",
            description = "修改学生**已有**成绩（总成绩自动重算，提交后影响学业记录）。成绩为**百分制 0~100**。"
                    + "教师说\"把这个学生的成绩改成 90\"\"刚才录错了\"时**直接调用本工具**："
                    + "提交前的确认由系统弹出的确认卡片负责，你不需要在文字里再问一次\"是否确认\"。"
                    + "只传需要改的那一项即可：usualScore / examScore **省略则保留库中原值，不会清零**。"
                    + "需要成绩记录ID（id）；只能修改本人所授课程的成绩，改他人课程的成绩会被拒绝。")
    @ToolMeta(displayName = "修改成绩", riskLevel = RiskLevel.DANGEROUS)
    public String updateScore(
            @ToolParam(description = "成绩记录ID（来自成绩列表，不是课程ID）", required = true)
            Integer id,
            @ToolParam(description = "平时成绩（0~100，可选；省略则沿用库中原值）", required = false)
            @ParamConstraint(min = 0, max = 100) Double usualScore,
            @ToolParam(description = "考试成绩（0~100，可选；省略则沿用库中原值）", required = false)
            @ParamConstraint(min = 0, max = 100) Double examScore,
            ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);

        Score existing = scoreService.selectById(id);
        if (existing == null) {
            return errorJson("成绩记录不存在");
        }
        // 归属校验：教师仅能修改自己课程的成绩
        if (!courseService.isCourseOfTeacher(teacherId, existing.getCourseId())) {
            return errorJson("无权限修改该成绩");
        }

        // 只传要改的那一项：ScoreService.update 会先合并库中原值再重算，
        // 因此另一项不会被清零，passed 也会随之重算（同样只有一处实现）。
        Score patch = new Score();
        patch.setId(id);
        if (usualScore != null) {
            patch.setUsualScore(BigDecimal.valueOf(usualScore));
        }
        if (examScore != null) {
            patch.setExamScore(BigDecimal.valueOf(examScore));
        }
        try {
            scoreService.update(patch);
        } catch (RuntimeException e) {
            return errorJson(e.getMessage());
        }

        Score updated = scoreService.selectById(id);
        return json("message", "成绩修改成功", "totalScore", updated.getTotalScore());
    }

    // ==================================================================================
    // 5. 学生评价（只读，且必须匿名）
    // ==================================================================================

    @Tool(name = "get_my_evaluations",
            description = "查看学生对本人的教学评价（**匿名**：结果里不含\"谁提交的\"，只有评分与文字内容）。"
                    + "教师问\"学生怎么评价我\"\"我的评教结果\"时使用；不需要任何参数。"
                    + "只返回本人收到的评价；如果教师追问\"是哪个学生评的\"，必须如实说明评价是匿名的、查不到提交人。")
    @ToolMeta(displayName = "学生评价", riskLevel = RiskLevel.READ_ONLY)
    public String getMyEvaluations(ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);
        // 必须走 listForTeacher：它会把"谁提交的"剥掉（匿名评教，剥离逻辑只有那一处实现）
        List<TeacherEvaluation> evaluations = evaluationService.listForTeacher(teacherId);
        return objectMapper.writeValueAsString(evaluations);
    }

    // ==================================================================================
    // 6. 提交开课申请（危险操作，弹确认卡片）
    // ==================================================================================

    @Tool(name = "submit_course_apply",
            description = "教师提交开课申请。当教师说\"我要申请开课\"\"我想开一门课\"\"申请开《XXX》\"时"
                    + "**直接调用本工具**：提交前的确认由系统弹出的确认卡片负责，"
                    + "你不需要在文字里再问一次\"是否确认\"。"
                    + "提交后只是进入待审批，管理员通过后才生成课程，学生也才能在选课轮次中选到该课。"
                    + "必填参数：courseCode（课程代码，同一门课各学期共用同一代码，是培养计划与已修判定的关联键）、"
                    + "courseName（课程名称）、term（开课学期，如 2024-2025-1）、"
                    + "credit（学分）、classHour（学时）、maxStudent（选课容量）。"
                    + "expectedWeekday/expectedStartPeriod/expectedEndPeriod/expectedStartWeek/expectedEndWeek "
                    + "是教师期望的上课时间，**要么全部省略，要么五个一起提供**（只给一部分会被拒绝并说明原因）；"
                    + "全部省略则不记录期望时间，之后可用 apply_class_time 单独申请排课。"
                    + "preferRoomId（期望教室ID）可省略，省略则由管理员在排课时分配教室。"
                    + "申请人一律取当前登录教师，不接受指定。"
                    + "必填参数缺失时系统会直接指出缺哪一项：此时请向教师询问那一项，"
                    + "不要编造课程代码或学期。")
    @ToolMeta(displayName = "提交开课申请", riskLevel = RiskLevel.DANGEROUS)
    public String submitCourseApply(
            @ToolParam(description = "课程代码，如 CS108（必填；同一门课各学期共用同一代码）", required = true)
            String courseCode,
            @ToolParam(description = "课程名称，如 编译原理（必填）", required = true)
            String courseName,
            @ToolParam(description = "开课学期，如 2024-2025-1（必填）", required = true)
            String term,
            @ToolParam(description = "学分，如 3.0（必填）", required = true)
            @ParamConstraint(min = 0) Double credit,
            @ToolParam(description = "学时，如 48（必填）", required = true)
            @ParamConstraint(min = 0) Integer classHour,
            @ToolParam(description = "选课容量（同时也是教学班容量），如 50（必填）", required = true)
            @ParamConstraint(min = 1) Integer maxStudent,
            @ToolParam(description = "期望星期几 1~7；省略则整体不记录期望时间"
                    + "（五个 expected* 必须同时给或同时省略）", required = false)
            @ParamConstraint(min = 1, max = 7) Integer expectedWeekday,
            @ToolParam(description = "期望起始节次 1~10；省略同上", required = false)
            @ParamConstraint(min = 1, max = 10) Integer expectedStartPeriod,
            @ToolParam(description = "期望结束节次 1~10；省略同上", required = false)
            @ParamConstraint(min = 1, max = 10) Integer expectedEndPeriod,
            @ToolParam(description = "期望起始周，如 1；省略同上", required = false)
            @ParamConstraint(min = 1, max = 53) Integer expectedStartWeek,
            @ToolParam(description = "期望结束周，如 16；省略同上", required = false)
            @ParamConstraint(min = 1, max = 53) Integer expectedEndWeek,
            // 手写版对 preferRoomId 没有下界约束（只要是个教室ID），因此这里也不加：
            // 迁移时"顺手加个 minimum=1"就是擅自收紧模型可见的取值域
            @ToolParam(description = "期望教室ID；省略则由管理员分配教室", required = false)
            Integer preferRoomId,
            ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);

        // 期望时间必须整体给出：服务端只在 expectedWeekday 非空时才校验区间，
        // 只给一半会被"静默接受"并落库成残缺的期望时间，因此这道检查必须留在工具侧
        // （与手写版同一措辞）。
        boolean anyExpected = expectedWeekday != null || expectedStartPeriod != null
                || expectedEndPeriod != null || expectedStartWeek != null || expectedEndWeek != null;
        boolean allExpected = expectedWeekday != null && expectedStartPeriod != null
                && expectedEndPeriod != null && expectedStartWeek != null && expectedEndWeek != null;
        if (anyExpected && !allExpected) {
            return errorJson("期望时间要么全部省略，要么同时提供 expectedWeekday/"
                    + "expectedStartPeriod/expectedEndPeriod/expectedStartWeek/expectedEndWeek");
        }

        CourseApply apply = new CourseApply();
        // 申请人一律取当前登录教师，绝不采用模型给的 teacherId（与 HTTP 路径同样防冒用）
        apply.setTeacherId(teacherId);
        apply.setCourseCode(courseCode);
        apply.setCourseName(courseName);
        apply.setTerm(term);
        apply.setCredit(credit == null ? null : BigDecimal.valueOf(credit));
        apply.setClassHour(classHour);
        apply.setMaxStudent(maxStudent);
        apply.setPreferRoomId(preferRoomId);
        if (allExpected) {
            apply.setExpectedWeekday(expectedWeekday);
            apply.setExpectedStartPeriod(expectedStartPeriod);
            apply.setExpectedEndPeriod(expectedEndPeriod);
            apply.setExpectedStartWeek(expectedStartWeek);
            apply.setExpectedEndWeek(expectedEndWeek);
        }

        try {
            CourseApply created = courseApplyService.submit(apply);
            return json("id", created.getId(),
                    "courseCode", created.getCourseCode(),
                    "courseName", created.getCourseName(),
                    "term", created.getTerm(),
                    "status", created.getStatus(),
                    "statusText", "待审批",
                    "message", "开课申请已提交，等待管理员审批。"
                            + "审批通过后才会生成课程，学生也才能在已开启的选课轮次中选到该课。");
        } catch (BusinessException e) {
            return errorJson(e.getMessage());
        }
    }

    // ==================================================================================
    // 7. 申请排课（危险操作，弹确认卡片）
    // ==================================================================================

    @Tool(name = "apply_class_time",
            description = "教师为**自己已审批通过**的课程申请上课时间（提交后进入待审批，管理员通过才真正排入课表）。"
                    + "教师说\"给这门课排个时间\"\"我想周三下午上\"\"申请排课\"时**直接调用本工具**："
                    + "提交前的确认由系统弹出的确认卡片负责，你不需要在文字里再问一次\"是否确认\"。"
                    + "必填参数：courseId（必须是本人授课的课程，可用 get_my_courses 查）、"
                    + "weekday（星期几 1~7）、startPeriod/endPeriod（起止节次 1~10，一天 10 节）、"
                    + "startWeek/endWeek（起止周，课程可以从中间周次开始，如第 9~16 周）。"
                    + "roomId（教室ID，可省略）——**省略则由系统在审批时自动推荐该时段容量足够的空闲教室**；"
                    + "填写则按该教室做占用校验。"
                    + "本工具**不会因为时间冲突而失败**：冲突会记录在返回的 conflictInfo 里，"
                    + "教师仍可提交、由管理员决定是否调整；**务必把 conflictInfo 如实转述给教师**，"
                    + "不要只说\"已提交\"。"
                    + "必填参数缺失时系统会直接指出缺哪一项：此时请向教师询问该参数，不要凭空猜节次或周次。")
    @ToolMeta(displayName = "申请排课", riskLevel = RiskLevel.DANGEROUS)
    public String applyClassTime(
            @ToolParam(description = "课程ID（必填，仅限本人授课的课程）", required = true)
            @ParamConstraint(min = 1) Integer courseId,
            @ToolParam(description = "星期几 1~7（1=周一，必填）", required = true)
            @ParamConstraint(min = 1, max = 7) Integer weekday,
            @ToolParam(description = "起始节次 1~10（必填）", required = true)
            @ParamConstraint(min = 1, max = 10) Integer startPeriod,
            @ToolParam(description = "结束节次 1~10，含该节（必填）", required = true)
            @ParamConstraint(min = 1, max = 10) Integer endPeriod,
            @ToolParam(description = "起始周（必填，如 1 或 9）", required = true)
            @ParamConstraint(min = 1, max = 53) Integer startWeek,
            @ToolParam(description = "结束周（必填，如 16）", required = true)
            @ParamConstraint(min = 1, max = 53) Integer endWeek,
            @ToolParam(description = "教室ID；省略则由系统自动推荐空闲教室", required = false)
            @ParamConstraint(min = 1) Integer roomId,
            ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);

        ClassTimeApply apply = new ClassTimeApply();
        apply.setCourseId(courseId);
        // 服务端会以"课程的授课教师"为准再校验归属（requireOwner=true），
        // 这里先填调用者本人，才能保证"只能为自己的课程排课"这道校验成立
        apply.setTeacherId(teacherId);
        apply.setWeekday(weekday);
        apply.setStartPeriod(startPeriod);
        apply.setEndPeriod(endPeriod);
        apply.setStartWeek(startWeek);
        apply.setEndWeek(endWeek);
        apply.setRoomId(roomId);

        try {
            ClassTimeApply saved = scheduleService.submitClassTimeApply(apply, true);
            String conflict = saved.getConflictInfo();
            if (conflict != null && !conflict.isBlank()) {
                // 有冲突不是失败：后端只记警告并落库，但模型必须如实转述，不能报"干净成功"
                return json("id", saved.getId(),
                        "courseId", saved.getCourseId(),
                        "weekday", saved.getWeekday(),
                        "startPeriod", saved.getStartPeriod(),
                        "endPeriod", saved.getEndPeriod(),
                        "startWeek", saved.getStartWeek(),
                        "endWeek", saved.getEndWeek(),
                        "roomId", saved.getRoomId(),
                        "status", saved.getStatus(),
                        "statusText", "待审批",
                        "conflictInfo", conflict,
                        "message", "排课申请已提交，但该时段存在冲突：" + conflict
                                + "。申请仍会进入待审批，由管理员决定是否调整时间或教室。");
            }
            return json("id", saved.getId(),
                    "courseId", saved.getCourseId(),
                    "weekday", saved.getWeekday(),
                    "startPeriod", saved.getStartPeriod(),
                    "endPeriod", saved.getEndPeriod(),
                    "startWeek", saved.getStartWeek(),
                    "endWeek", saved.getEndWeek(),
                    "roomId", saved.getRoomId(),
                    "status", saved.getStatus(),
                    "statusText", "待审批",
                    "message", "排课申请已提交，未检测到时间冲突，等待管理员审批。");
        } catch (BusinessException e) {
            return errorJson(e.getMessage());
        }
    }

    // ==================================================================================
    // 内部小工具
    // ==================================================================================

    /**
     * 组装返回 JSON（保持字段顺序，便于人读与脚本断言）。
     *
     * <p>用 {@code LinkedHashMap} 而不是 {@code Map.of}：后者的键顺序不保证，
     * 而这些返回结构是要给模型读、也会被人肉核对的。
     */
    private String json(Object... keyValues) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return objectMapper.writeValueAsString(map);
    }

    private String messageJson(String message) throws Exception {
        return json("message", message);
    }

    /** 业务失败也返回 JSON（而不是抛异常）：抛异常会被上层替换成模型无法利用的通用错误 */
    private String errorJson(String message) throws Exception {
        return json("error", message == null || message.isBlank() ? "操作失败" : message);
    }

    /** 可空的成绩转 BigDecimal；省略（null）按 0 分计（与手写版一致） */
    private static BigDecimal toDecimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    /** 学号转整数；非纯数字返回 null（由调用方给出可读原因，不要抛 NumberFormatException） */
    private static Integer parseStudentId(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(studentId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

