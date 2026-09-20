package duyell.ai.tool;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.*;
import duyell.service.CourseApplyService;
import duyell.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import utils.BusinessException;

import java.math.BigDecimal;
import java.util.*;

@Component
@RequiredArgsConstructor
public class AdminToolRegistrar implements InitializingBean {

    private final ToolRegistry registry;
    private final SysUserMapper sysUserMapper;
    private final StudentMapper studentMapper;
    private final TeacherMapper teacherMapper;
    private final CourseMapper courseMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final ClazzMapper clazzMapper;
    private final HomeService homeService;
    private final CourseApplyService courseApplyService;
    private final ObjectMapper objectMapper;

    /** 查询类工具统一使用 READ_ONLY；写操作（如审批开课）逐个显式标注 DANGEROUS */
    private static final RiskLevel READ_ONLY = RiskLevel.READ_ONLY;

    @Override
    public void afterPropertiesSet() {
        registry.register("admin", new ToolDefinition(
                "get_statistics", "系统统计", "获取系统统计数据（学生数、教师数、课程数、班级数）",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    Map<String, Object> stats = homeService.getStatistics();
                    return objectMapper.writeValueAsString(stats);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_users", "用户列表",
                "查询系统用户列表。所有筛选参数均可省略，省略时返回全部用户（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "role", Map.of("type", "string",
                                        "enum", List.of("admin", "teacher", "student"),
                                        "description", "按角色筛选；省略则不限制角色"),
                                "username", Map.of("type", "string",
                                        "description", "用户名关键词；省略则不限制")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String roleFilter = (String) args.getOrDefault("role", null);
                    String username = (String) args.getOrDefault("username", null);
                    // 限制最多返回50条
                    List<SysUser> users = sysUserMapper.list(roleFilter, username);
                    if (users.size() > 50) users = users.subList(0, 50);
                    return objectMapper.writeValueAsString(users);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_students", "学生列表",
                "查询学生列表。所有筛选参数均可省略，省略时返回全部学生（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "studentName", Map.of("type", "string",
                                        "description", "学生姓名关键词；省略则不限制"),
                                "studentId", Map.of("type", "string",
                                        "description", "学生学号关键词；省略则不限制")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String studentName = (String) args.getOrDefault("studentName", null);
                    String studentId = (String) args.getOrDefault("studentId", null);
                    List<Student> students = studentMapper.list(studentName, studentId, null, null, null);
                    if (students.size() > 50) students = students.subList(0, 50);
                    return objectMapper.writeValueAsString(students);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_teachers", "教师列表",
                "查询教师列表。所有筛选参数均可省略，省略时返回全部教师（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "teacherName", Map.of("type", "string",
                                        "description", "教师姓名关键词；省略则不限制"),
                                "teacherId", Map.of("type", "string",
                                        "description", "教师工号关键词；省略则不限制")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String teacherName = (String) args.getOrDefault("teacherName", null);
                    String teacherId = (String) args.getOrDefault("teacherId", null);
                    List<Teacher> teachers = teacherMapper.list(teacherName, teacherId, null, null);
                    if (teachers.size() > 50) teachers = teachers.subList(0, 50);
                    return objectMapper.writeValueAsString(teachers);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_courses", "课程列表",
                "查询课程列表。省略 courseName 时返回全部课程（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseName", Map.of("type", "string",
                                        "description", "课程名称关键词；省略则返回全部")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String courseName = (String) args.getOrDefault("courseName", null);
                    List<Course> courses = courseMapper.list(courseName, null, null, null, null, null, null);
                    if (courses.size() > 50) courses = courses.subList(0, 50);
                    return objectMapper.writeValueAsString(courses);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_colleges", "学院列表", "查询所有学院列表",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    List<College> colleges = collegeMapper.list(null);
                    return objectMapper.writeValueAsString(colleges);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_majors", "专业列表", "查询所有专业列表",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    List<Major> majors = majorMapper.list(null, null);
                    return objectMapper.writeValueAsString(majors);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_classes", "班级列表", "查询所有班级列表",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    List<Clazz> classes = clazzMapper.list(null, null, null, null);
                    return objectMapper.writeValueAsString(classes);
                }
        ));

        // ---------- P5：开课申请审批（写操作，必须人工确认） ----------

        registry.register("admin", new ToolDefinition(
                "approve_course_apply", "审批通过开课申请",
                "管理员审批通过一条**待审批（PENDING）**的开课申请，通过后系统会据申请生成课程记录。"
                        + "当管理员说\"通过这条开课申请\"\"批准 XXX 老师开课\"时使用本工具。"
                        + "参数：applyId（开课申请ID，必填，可用 list_courses 之外的申请列表页查看，或让教师提供）。"
                        + "只会通过 PENDING 的申请——已通过或已驳回的会被拒绝（不会重复生成课程）。"
                        + "审批通过**不等于**学生马上能选到：该课程还需要教师申请排课，"
                        + "且需要管理员开启选课轮次后学生才能选。"
                        + "审批人一律取当前登录账号，不接受指定。"
                        + "缺少 applyId 时返回 {\"error\":\"缺少参数 applyId\"}，此时请向管理员询问要审批的申请编号。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "applyId", Map.of("type", "integer", "minimum", 1,
                                        "description", "开课申请ID（必填，且该申请需处于待审批状态）")
                        ),
                        "required", List.of("applyId")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    String err = firstError(requireArgs(args, "applyId"),
                            checkInt(args, "applyId", 1, Integer.MAX_VALUE, true));
                    if (err != null) {
                        return errorJson(err);
                    }
                    try {
                        CourseApply approved = courseApplyService.approve(intOf(args, "applyId"), userId);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("id", approved.getId());
                        result.put("courseCode", approved.getCourseCode());
                        result.put("courseName", approved.getCourseName());
                        result.put("term", approved.getTerm());
                        result.put("status", approved.getStatus());
                        result.put("statusText", "已通过");
                        // 生成的课程行 id：后续申请排课、以及学生选课都围绕它进行
                        result.put("createdCourseId", approved.getCreatedCourseId());
                        result.put("message", "开课申请已通过，已生成课程（courseId=" + approved.getCreatedCourseId()
                                + "，课程代码 " + approved.getCourseCode() + "）。"
                                + "该课程还需教师申请排课，且需开启选课轮次后学生才能选到。");
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
    // 因此执行前自己校验一遍，并返回 {"error":"..."} 让模型能解释失败原因。

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

    /** 业务失败也返回 JSON（而不是抛异常）：抛异常会被上层替换成模型无法利用的通用错误 */
    private String errorJson(String message) throws Exception {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message == null || message.isBlank() ? "操作失败" : message);
        return objectMapper.writeValueAsString(error);
    }
}
