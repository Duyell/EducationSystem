package duyell.ai.tool;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.*;
import duyell.service.CourseApplyService;
import duyell.service.CourseService;
import duyell.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import utils.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
@RequiredArgsConstructor
public class TeacherToolRegistrar implements InitializingBean {

    private final ToolRegistry registry;
    private final CourseMapper courseMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final ScoreMapper scoreMapper;
    private final EvaluationMapper evaluationMapper;
    private final StudentMapper studentMapper;
    private final CourseService courseService;
    private final CourseApplyService courseApplyService;
    private final ScheduleService scheduleService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterPropertiesSet() {
        registry.register("teacher", new ToolDefinition(
                "get_my_courses", "我的授课课程", "获取当前教师教授的课程列表",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<Course> courses = courseMapper.selectByTeacherId(userId);
                    return objectMapper.writeValueAsString(courses);
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "get_course_students", "课程学生名单", "查看某门课程的所有选课学生",
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
                    // 归属校验：教师仅能查看自己课程的选课名单
                    if (!courseService.isCourseOfTeacher(userId, courseId)) {
                        return "{\"error\":\"无权限查看该课程的选课名单\"}";
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
        ));

        registry.register("teacher", new ToolDefinition(
                "enter_score", "录入成绩", "录入学生成绩（平时成绩和考试成绩，总成绩=平时×0.4+考试×0.6）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID"),
                                "studentId", Map.of("type", "string", "description", "学生学号"),
                                "usualScore", Map.of("type", "number", "description", "平时成绩（可选，默认0）"),
                                "examScore", Map.of("type", "number", "description", "考试成绩（可选，默认0）")
                        ),
                        "required", List.of("courseId", "studentId")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    // 归属校验：教师仅能为自己课程的选课学生录入成绩
                    if (!courseService.isCourseOfTeacher(userId, courseId)) {
                        return "{\"error\":\"无权限为该课程录入成绩\"}";
                    }
                    String studentId = (String) args.get("studentId");
                    BigDecimal usualScore = args.containsKey("usualScore")
                            ? BigDecimal.valueOf(Double.parseDouble(args.get("usualScore").toString()))
                            : BigDecimal.ZERO;
                    BigDecimal examScore = args.containsKey("examScore")
                            ? BigDecimal.valueOf(Double.parseDouble(args.get("examScore").toString()))
                            : BigDecimal.ZERO;
                    // total = usualScore * 0.4 + examScore * 0.6
                    BigDecimal total = usualScore.multiply(BigDecimal.valueOf(0.4))
                            .add(examScore.multiply(BigDecimal.valueOf(0.6)))
                            .setScale(1, RoundingMode.HALF_UP);

                    // 检查是否已存在成绩
                    Score existing = scoreMapper.select(courseId, Integer.valueOf(studentId));
                    if (existing != null) {
                        return "{\"message\":\"该学生此课程已有成绩记录，请使用 update_score 修改\"}";
                    }

                    Score score = new Score();
                    score.setCourseId(courseId);
                    score.setStudentId(studentId);
                    score.setUsualScore(usualScore);
                    score.setExamScore(examScore);
                    score.setTotalScore(total);
                    scoreMapper.add(score);
                    return "{\"message\":\"成绩录入成功\",\"totalScore\":" + total + "}";
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "update_score", "修改成绩",
                "修改学生已有成绩（总成绩自动重算，提交后影响学业记录）。"
                        + "只传需要改的那一项即可：usualScore / examScore 省略则保留库中原值，不会清零。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "integer", "description", "成绩记录ID"),
                                "usualScore", Map.of("type", "number", "description", "平时成绩；省略则沿用原值"),
                                "examScore", Map.of("type", "number", "description", "考试成绩；省略则沿用原值")
                        ),
                        "required", List.of("id")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer id = Integer.valueOf(args.get("id").toString());

                    Score existing = scoreMapper.selectById(id);
                    if (existing == null) {
                        return "{\"error\":\"成绩记录不存在\"}";
                    }
                    // 归属校验：教师仅能修改自己课程的成绩
                    if (!courseService.isCourseOfTeacher(userId, existing.getCourseId())) {
                        return "{\"error\":\"无权限修改该成绩\"}";
                    }

                    // 合并更新：只传平时成绩时保留原考试成绩，避免被清零
                    BigDecimal usual = existing.getUsualScore() != null ? existing.getUsualScore() : BigDecimal.ZERO;
                    BigDecimal exam = existing.getExamScore() != null ? existing.getExamScore() : BigDecimal.ZERO;
                    if (args.containsKey("usualScore")) {
                        usual = BigDecimal.valueOf(Double.parseDouble(args.get("usualScore").toString()));
                    }
                    if (args.containsKey("examScore")) {
                        exam = BigDecimal.valueOf(Double.parseDouble(args.get("examScore").toString()));
                    }
                    BigDecimal total = usual.multiply(BigDecimal.valueOf(0.4))
                            .add(exam.multiply(BigDecimal.valueOf(0.6)))
                            .setScale(1, RoundingMode.HALF_UP);

                    Score score = new Score();
                    score.setId(id);
                    score.setUsualScore(usual);
                    score.setExamScore(exam);
                    score.setTotalScore(total);
                    scoreMapper.update(score);
                    return "{\"message\":\"成绩修改成功\",\"totalScore\":" + total + "}";
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "get_my_evaluations", "学生评价", "查看学生对当前教师的教学评价",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<TeacherEvaluation> evaluations = evaluationMapper.list(null, null, userId);
                    return objectMapper.writeValueAsString(evaluations);
                }
        ));

        // ---------- P5：开课申请 与 排课申请（两个审批流的入口，均需人工确认） ----------

        registry.register("teacher", new ToolDefinition(
                "submit_course_apply", "提交开课申请",
                "教师提交开课申请。当教师说\"我要申请开课\"\"我想开一门课\"\"申请开《XXX》\"时使用本工具。"
                        + "提交后只是进入待审批，管理员通过后才生成课程，学生也才能在选课轮次中选到该课。"
                        + "参数：courseCode（课程代码，必填，同一门课各学期共用同一代码，是培养计划与已修判定的关联键）、"
                        + "courseName（课程名称，必填）、term（开课学期，必填，如 2024-2025-1）、"
                        + "credit（学分，必填）、classHour（学时，必填）、maxStudent（选课容量，必填）。"
                        + "expectedWeekday/expectedStartPeriod/expectedEndPeriod/expectedStartWeek/expectedEndWeek "
                        + "是教师期望的上课时间，**要么全部省略，要么五个一起提供**（只给一部分会被拒绝）；"
                        + "全部省略则不记录期望时间，之后可用 apply_class_time 单独申请排课。"
                        + "preferRoomId（期望教室ID）可省略，省略则由管理员在排课时分配。"
                        + "申请人一律取当前登录教师，不接受指定。"
                        + "缺少任一必填参数时本工具会返回 {\"error\":\"缺少参数 X\"}——"
                        + "此时请向教师询问那一项，不要编造课程代码或学期。",
                Map.of(
                        "type", "object",
                        "properties", Map.ofEntries(
                                Map.entry("courseCode", Map.of("type", "string",
                                        "description", "课程代码，如 CS108（必填）")),
                                Map.entry("courseName", Map.of("type", "string",
                                        "description", "课程名称，如 编译原理（必填）")),
                                Map.entry("term", Map.of("type", "string",
                                        "description", "开课学期，如 2024-2025-1（必填）")),
                                Map.entry("credit", Map.of("type", "number", "minimum", 0,
                                        "description", "学分，如 3.0（必填）")),
                                Map.entry("classHour", Map.of("type", "integer", "minimum", 0,
                                        "description", "学时，如 48（必填）")),
                                Map.entry("maxStudent", Map.of("type", "integer", "minimum", 1,
                                        "description", "选课容量（同时也是教学班容量），如 50（必填）")),
                                Map.entry("expectedWeekday", Map.of("type", "integer", "minimum", 1, "maximum", 7,
                                        "description", "期望星期几 1~7；省略则整体不记录期望时间（五个 expected* 必须同时给或同时不给）")),
                                Map.entry("expectedStartPeriod", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                                        "description", "期望起始节次 1~10；省略同上")),
                                Map.entry("expectedEndPeriod", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                                        "description", "期望结束节次 1~10；省略同上")),
                                Map.entry("expectedStartWeek", Map.of("type", "integer", "minimum", 1, "maximum", 53,
                                        "description", "期望起始周，如 1；省略同上")),
                                Map.entry("expectedEndWeek", Map.of("type", "integer", "minimum", 1, "maximum", 53,
                                        "description", "期望结束周，如 16；省略同上")),
                                Map.entry("preferRoomId", Map.of("type", "integer",
                                        "description", "期望教室ID；省略则由管理员分配教室"))
                        ),
                        "required", List.of("courseCode", "courseName", "term", "credit", "classHour", "maxStudent")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    String err = firstError(
                            requireArgs(args, "courseCode", "courseName", "term", "credit", "classHour", "maxStudent"),
                            checkInt(args, "classHour", 0, Integer.MAX_VALUE, true),
                            checkInt(args, "maxStudent", 1, Integer.MAX_VALUE, true),
                            checkInt(args, "expectedWeekday", 1, 7, false),
                            checkInt(args, "expectedStartPeriod", 1, 10, false),
                            checkInt(args, "expectedEndPeriod", 1, 10, false),
                            checkInt(args, "expectedStartWeek", 1, 53, false),
                            checkInt(args, "expectedEndWeek", 1, 53, false),
                            checkInt(args, "preferRoomId", 1, Integer.MAX_VALUE, false));
                    if (err != null) {
                        return errorJson(err);
                    }
                    // 期望时间必须整体给出：只给一半会让服务端的区间校验报出难以理解的错误
                    boolean anyExpected = hasValue(args, "expectedWeekday") || hasValue(args, "expectedStartPeriod")
                            || hasValue(args, "expectedEndPeriod") || hasValue(args, "expectedStartWeek")
                            || hasValue(args, "expectedEndWeek");
                    boolean allExpected = hasValue(args, "expectedWeekday") && hasValue(args, "expectedStartPeriod")
                            && hasValue(args, "expectedEndPeriod") && hasValue(args, "expectedStartWeek")
                            && hasValue(args, "expectedEndWeek");
                    if (anyExpected && !allExpected) {
                        return errorJson("期望时间要么全部省略，要么同时提供 expectedWeekday/"
                                + "expectedStartPeriod/expectedEndPeriod/expectedStartWeek/expectedEndWeek");
                    }

                    CourseApply apply = new CourseApply();
                    // 申请人一律取当前登录教师，绝不采用模型给的 teacherId（与 HTTP 路径同样防冒用）
                    apply.setTeacherId(userId);
                    apply.setCourseCode(strOf(args, "courseCode"));
                    apply.setCourseName(strOf(args, "courseName"));
                    apply.setTerm(strOf(args, "term"));
                    apply.setCredit(decimalOf(args, "credit"));
                    apply.setClassHour(intOf(args, "classHour"));
                    apply.setMaxStudent(intOf(args, "maxStudent"));
                    apply.setPreferRoomId(intOf(args, "preferRoomId"));
                    if (allExpected) {
                        apply.setExpectedWeekday(intOf(args, "expectedWeekday"));
                        apply.setExpectedStartPeriod(intOf(args, "expectedStartPeriod"));
                        apply.setExpectedEndPeriod(intOf(args, "expectedEndPeriod"));
                        apply.setExpectedStartWeek(intOf(args, "expectedStartWeek"));
                        apply.setExpectedEndWeek(intOf(args, "expectedEndWeek"));
                    }

                    try {
                        CourseApply created = courseApplyService.submit(apply);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("id", created.getId());
                        result.put("courseCode", created.getCourseCode());
                        result.put("courseName", created.getCourseName());
                        result.put("term", created.getTerm());
                        result.put("status", created.getStatus());
                        result.put("statusText", "待审批");
                        result.put("message", "开课申请已提交，等待管理员审批。"
                                + "审批通过后才会生成课程，学生也才能在已开启的选课轮次中选到该课。");
                        return objectMapper.writeValueAsString(result);
                    } catch (BusinessException e) {
                        return errorJson(e.getMessage());
                    }
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "apply_class_time", "申请排课",
                "教师为**自己已审批通过**的课程申请上课时间（提交后进入待审批，管理员通过才真正排入课表）。"
                        + "当教师说\"给这门课排个时间\"\"我想周三下午上\"\"申请排课\"时使用本工具。"
                        + "参数：courseId（课程ID，必填，必须是本人授课的课程，可用 get_my_courses 查）、"
                        + "weekday（星期几 1~7，必填）、startPeriod/endPeriod（起止节次 1~10，必填，一天 10 节）、"
                        + "startWeek/endWeek（起止周，必填，课程可以从中间周次开始，如第 9~16 周）。"
                        + "roomId（教室ID，可省略）——**省略则由系统在审批时自动推荐该时段容量足够的空闲教室**；"
                        + "填写则按该教室做占用校验。"
                        + "本工具**不会因为时间冲突而失败**：冲突会记录在返回的 conflictInfo 里，"
                        + "教师仍可提交、由管理员决定是否调整；请务必把 conflictInfo 如实转述给教师，不要只说\"已提交\"。"
                        + "缺少任一必填参数时返回 {\"error\":\"缺少参数 X\"}，此时请向教师询问该参数。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "minimum", 1,
                                        "description", "课程ID（必填，仅限本人授课的课程）"),
                                "weekday", Map.of("type", "integer", "minimum", 1, "maximum", 7,
                                        "description", "星期几 1~7（1=周一，必填）"),
                                "startPeriod", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                                        "description", "起始节次 1~10（必填）"),
                                "endPeriod", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                                        "description", "结束节次 1~10，含该节（必填）"),
                                "startWeek", Map.of("type", "integer", "minimum", 1, "maximum", 53,
                                        "description", "起始周（必填，如 1 或 9）"),
                                "endWeek", Map.of("type", "integer", "minimum", 1, "maximum", 53,
                                        "description", "结束周（必填，如 16）"),
                                "roomId", Map.of("type", "integer", "minimum", 1,
                                        "description", "教室ID；**省略则由系统自动推荐空闲教室**")
                        ),
                        "required", List.of("courseId", "weekday", "startPeriod", "endPeriod", "startWeek", "endWeek")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    String err = firstError(
                            requireArgs(args, "courseId", "weekday", "startPeriod", "endPeriod", "startWeek", "endWeek"),
                            checkInt(args, "courseId", 1, Integer.MAX_VALUE, true),
                            checkInt(args, "weekday", 1, 7, true),
                            checkInt(args, "startPeriod", 1, 10, true),
                            checkInt(args, "endPeriod", 1, 10, true),
                            checkInt(args, "startWeek", 1, 53, true),
                            checkInt(args, "endWeek", 1, 53, true),
                            checkInt(args, "roomId", 1, Integer.MAX_VALUE, false));
                    if (err != null) {
                        return errorJson(err);
                    }

                    ClassTimeApply apply = new ClassTimeApply();
                    apply.setCourseId(intOf(args, "courseId"));
                    // 服务端会以"课程的授课教师"为准再校验归属（requireOwner=true），
                    // 这里先填调用者本人，才能保证"只能为自己的课程排课"这道校验成立
                    apply.setTeacherId(userId);
                    apply.setWeekday(intOf(args, "weekday"));
                    apply.setStartPeriod(intOf(args, "startPeriod"));
                    apply.setEndPeriod(intOf(args, "endPeriod"));
                    apply.setStartWeek(intOf(args, "startWeek"));
                    apply.setEndWeek(intOf(args, "endWeek"));
                    apply.setRoomId(intOf(args, "roomId"));

                    try {
                        ClassTimeApply saved = scheduleService.submitClassTimeApply(apply, true);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("id", saved.getId());
                        result.put("courseId", saved.getCourseId());
                        result.put("weekday", saved.getWeekday());
                        result.put("startPeriod", saved.getStartPeriod());
                        result.put("endPeriod", saved.getEndPeriod());
                        result.put("startWeek", saved.getStartWeek());
                        result.put("endWeek", saved.getEndWeek());
                        result.put("roomId", saved.getRoomId());
                        result.put("status", saved.getStatus());
                        result.put("statusText", "待审批");
                        String conflict = saved.getConflictInfo();
                        if (conflict != null && !conflict.isBlank()) {
                            // 有冲突不是失败：后端只记警告并落库，但模型必须如实转述，不能报"干净成功"
                            result.put("conflictInfo", conflict);
                            result.put("message", "排课申请已提交，但该时段存在冲突：" + conflict
                                    + "。申请仍会进入待审批，由管理员决定是否调整时间或教室。");
                        } else {
                            result.put("message", "排课申请已提交，未检测到时间冲突，等待管理员审批。");
                        }
                        return objectMapper.writeValueAsString(result);
                    } catch (BusinessException e) {
                        return errorJson(e.getMessage());
                    }
                }
        ));
    }

    private Map<String, Object> noParams() {
        return Map.of("type", "object", "properties", Map.of());
    }

    // ===================== 工具参数读取与校验 =====================
    // 模型可能漏传 schema 里标了 required 的参数，也可能传来无法解析的值，
    // 因此每个工具执行前都自己校验一遍，并返回 {"error":"..."} 让模型能解释失败原因。

    /** 缺少必填参数时返回中文提示，全部存在则返回 null */
    private static String requireArgs(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            if (!hasValue(args, key)) {
                return "缺少参数 " + key;
            }
        }
        return null;
    }

    /** 参数是否存在且非空白 */
    private static boolean hasValue(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null && !String.valueOf(v).trim().isEmpty();
    }

    /**
     * 整数参数校验。
     *
     * @param required 为 false 时允许省略（省略即返回 null，不算错误）
     */
    private static String checkInt(Map<String, Object> args, String key, int min, int max, boolean required) {
        if (!hasValue(args, key)) {
            return required ? "缺少参数 " + key : null;
        }
        int value;
        try {
            value = intOf(args, key);
        } catch (NumberFormatException e) {
            return key + " 必须是整数，实际为 " + args.get(key);
        }
        if (value < min || value > max) {
            return key + " 必须在 " + min + "~" + max + " 之间，实际为 " + value;
        }
        return null;
    }

    /** 返回第一个非 null 的错误信息（把多个校验串起来写） */
    private static String firstError(String... errors) {
        for (String e : errors) {
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    /** 读取整数参数；缺失返回 null，无法解析抛 NumberFormatException */
    private static Integer intOf(Map<String, Object> args, String key) {
        if (!hasValue(args, key)) {
            return null;
        }
        return new BigDecimal(String.valueOf(args.get(key)).trim()).intValue();
    }

    /** 读取小数参数；缺失返回 null */
    private static BigDecimal decimalOf(Map<String, Object> args, String key) {
        if (!hasValue(args, key)) {
            return null;
        }
        return new BigDecimal(String.valueOf(args.get(key)).trim());
    }

    /** 读取字符串参数（去首尾空白）；缺失返回 null */
    private static String strOf(Map<String, Object> args, String key) {
        if (!hasValue(args, key)) {
            return null;
        }
        return String.valueOf(args.get(key)).trim();
    }

    /** 业务失败也返回 JSON（而不是抛异常）：抛异常会被上层替换成模型无法利用的通用错误 */
    private String errorJson(String message) throws Exception {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message == null || message.isBlank() ? "操作失败" : message);
        return objectMapper.writeValueAsString(error);
    }
}
