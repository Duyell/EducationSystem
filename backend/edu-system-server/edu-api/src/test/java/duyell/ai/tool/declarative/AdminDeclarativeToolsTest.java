package duyell.ai.tool.declarative;

import com.duyell.Course;
import com.duyell.CourseApply;
import com.duyell.SysUser;
import com.duyell.College;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import duyell.mapper.ClazzMapper;
import duyell.mapper.CollegeMapper;
import duyell.mapper.CourseApplyMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.MajorMapper;
import duyell.mapper.StudentMapper;
import duyell.mapper.SysUserMapper;
import duyell.mapper.TeacherMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理员工具声明式迁移测试（M2 计划 1.3 批量迁移：管理员全量）。
 *
 * <p>迁移的风险从来不是"框架能不能跑"，而是**换了声明方式之后契约有没有被悄悄改掉**：
 * 工具名/展示名/风险等级、参数 Schema 的**形状**、返回值结构、以及调用者身份的来源。
 * 因此这里的期望值全部**按手写版（{@code AdminToolRegistrar}）硬编码在断言里**——
 * 手写定义在启动时已被声明式版本覆盖注册，运行时查不到"旧的一份"，
 * 把这些值写死在测试里反而是唯一能发现"两边漂移"的办法。
 *
 * <p>重点盯住两件事：
 * <ol>
 *   <li>{@code approve_course_apply} 是**写操作**，必须仍是 {@code DANGEROUS}
 *       （{@code requiresConfirmation()} 为 true 才会弹确认卡片；标错就等于绕过人工确认）；</li>
 *   <li>审批人只能来自 token（{@code ToolContext}），**绝不能**进参数 Schema——
 *       否则模型能伪造审批痕迹。</li>
 * </ol>
 *
 * <p>业务数据断言（不是"非空"）：统计数字与 mapper 的计数逐个对齐、
 * 关键词过滤真的生效、审批真的生成课程且审批人是当前登录账号。
 * 用真实库 + 回滚事务隔离，可反复跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AdminDeclarativeToolsTest {

    /** 管理员账号（种子数据里的 admin01） */
    private static final String ADMIN = "admin01";

    private static final RiskLevel READ_ONLY = RiskLevel.READ_ONLY;
    private static final RiskLevel DANGEROUS = RiskLevel.DANGEROUS;

    /**
     * 一个迁移工具的**手写版契约**（期望值）。
     *
     * @param name          工具名（必须与手写版完全一致）
     * @param displayName   中文展示名（确认卡片/状态/审计里给人看的名字）
     * @param riskLevel     风险等级（写操作必须是 DANGEROUS）
     * @param required      必填参数（顺序即声明顺序）
     * @param propertyTypes 参数名 → JSON Schema 里的类型
     */
    private record Expected(String name, String displayName, RiskLevel riskLevel,
                            List<String> required, Map<String, String> propertyTypes) {
    }

    /** `AdminToolRegistrar` 里全部 9 个工具的契约（逐字抄自手写定义） */
    private static final List<Expected> MIGRATED = List.of(
            new Expected("get_statistics", "系统统计", READ_ONLY, List.of(), Map.of()),
            new Expected("list_users", "用户列表", READ_ONLY, List.of(),
                    Map.of("role", "string", "username", "string")),
            new Expected("list_students", "学生列表", READ_ONLY, List.of(),
                    Map.of("studentName", "string", "studentId", "string")),
            new Expected("list_teachers", "教师列表", READ_ONLY, List.of(),
                    Map.of("teacherName", "string", "teacherId", "string")),
            new Expected("list_courses", "课程列表", READ_ONLY, List.of(),
                    Map.of("courseName", "string")),
            new Expected("list_colleges", "学院列表", READ_ONLY, List.of(), Map.of()),
            new Expected("list_majors", "专业列表", READ_ONLY, List.of(), Map.of()),
            new Expected("list_classes", "班级列表", READ_ONLY, List.of(), Map.of()),
            new Expected("approve_course_apply", "审批通过开课申请", DANGEROUS, List.of("applyId"),
                    Map.of("applyId", "integer")));

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private StudentMapper studentMapper;

    @Autowired
    private TeacherMapper teacherMapper;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private CollegeMapper collegeMapper;

    @Autowired
    private MajorMapper majorMapper;

    @Autowired
    private ClazzMapper clazzMapper;

    @Autowired
    private CourseApplyMapper courseApplyMapper;

    // ---------------------------------------------------------------- 工具

    private ToolDefinition adminTool(String name) {
        ToolDefinition def = registry.getTool("admin", name);
        assertNotNull(def, "admin 角色下应有工具 " + name + "（迁移后必须仍然可见）");
        return def;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(ToolDefinition def) {
        Object props = def.parameters() == null ? null : def.parameters().get("properties");
        return props instanceof Map ? (Map<String, Object>) props : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> required(ToolDefinition def) {
        Object req = def.parameters() == null ? null : def.parameters().get("required");
        return req instanceof List ? (List<String>) req : List.of();
    }

    private String run(String toolName, Map<String, Object> args) {
        ToolExecutionResult result = registry.executeForRole(adminTool(toolName), "admin", args, ADMIN);
        assertTrue(result.isSuccess(), toolName + " 应执行成功，实际 " + result.status()
                + " / " + result.errorDetail());
        assertNotNull(result.payload(), toolName + " 应返回载荷");
        return result.payload();
    }

    private Map<String, Object> runJson(String toolName, Map<String, Object> args) throws Exception {
        return objectMapper.readValue(run(toolName, args), new TypeReference<>() {
        });
    }

    /** 参数名集合比较用：Schema 是 Map、期望值是 Map，键序都不保证，因此排序后再比 */
    private static List<String> sorted(java.util.Collection<String> values) {
        return values.stream().sorted().toList();
    }

    // ---------------------------------------------------------------- 契约

    /** 9 个工具全部在 admin 角色下、名字/展示名/风险等级与手写版逐字一致 */
    @Test
    void everyMigratedToolKeepsItsNameDisplayNameAndRiskLevel() {
        for (Expected expected : MIGRATED) {
            ToolDefinition def = adminTool(expected.name());
            assertEquals(expected.name(), def.name(), "工具名不得改变（模型与审计都按它索引）");
            assertEquals(expected.displayName(), def.displayName(),
                    expected.name() + " 的展示名必须与手写版一致（确认卡片/审计里给人看的就是它）");
            assertEquals(expected.riskLevel(), def.riskLevel(),
                    expected.name() + " 的风险等级必须与手写版一致");
            assertEquals(expected.riskLevel() == DANGEROUS, def.requiresConfirmation(),
                    expected.name() + " 的 requiresConfirmation 与风险等级不符（HITL 开关）");
        }
    }

    /** 写操作必须要求人工确认：审批一旦被标成只读就会直接执行 */
    @Test
    void theOnlyWriteToolStillRequiresConfirmation() {
        ToolDefinition approve = adminTool("approve_course_apply");
        assertEquals(DANGEROUS, approve.riskLevel());
        assertTrue(approve.requiresConfirmation(),
                "审批开课申请是写操作，必须弹确认卡片（标成 READ_ONLY 会绕过人工确认）");

        // 其余 8 个都是查询：不该要求确认
        for (Expected expected : MIGRATED) {
            if ("approve_course_apply".equals(expected.name())) {
                continue;
            }
            assertFalse(adminTool(expected.name()).requiresConfirmation(),
                    expected.name() + " 是只读查询，不该要求确认");
        }
    }

    /** 参数 Schema 形状：property 名、类型、required 都必须与手写版一致 */
    @Test
    void everyMigratedToolKeepsTheParameterShape() {
        for (Expected expected : MIGRATED) {
            ToolDefinition def = adminTool(expected.name());
            Map<String, Object> props = properties(def);

            assertEquals(sorted(List.copyOf(expected.propertyTypes().keySet())), sorted(props.keySet()),
                    expected.name() + " 的参数名集合与手写版不一致，实际=" + props.keySet());
            assertEquals(expected.required(), required(def),
                    expected.name() + " 的必填参数与手写版不一致");

            for (Map.Entry<String, String> type : expected.propertyTypes().entrySet()) {
                Object schema = props.get(type.getKey());
                assertTrue(String.valueOf(schema).contains("\"" + type.getValue() + "\"")
                                || String.valueOf(schema).contains(type.getValue()),
                        expected.name() + " 的参数 " + type.getKey() + " 类型应为 " + type.getValue()
                                + "，实际=" + schema);
            }
        }
    }

    /**
     * 调用者身份不得出现在参数 Schema 里。
     *
     * <p>注意 {@code list_users} 本来就有一个业务参数叫 {@code role}，所以这里**只禁**
     * {@code userId} / {@code toolContext}：把合法的 role 参数一起禁掉会误伤。
     */
    @Test
    void callerIdentityNeverAppearsInTheSchema() {
        for (Expected expected : MIGRATED) {
            String schema = String.valueOf(adminTool(expected.name()).parameters());
            assertFalse(schema.contains("userId"),
                    expected.name() + " 的参数 Schema 不得出现 userId：" + schema);
            assertFalse(schema.toLowerCase().contains("toolcontext"),
                    expected.name() + " 的参数 Schema 不得出现 ToolContext：" + schema);
        }
    }

    /** 角色隔离：管理员工具不得出现在学生/教师的工具列表里 */
    @Test
    void adminToolsAreIsolatedFromStudentAndTeacher() {
        List<String> studentTools = registry.getToolsByRole("student").stream()
                .map(ToolDefinition::name).toList();
        List<String> teacherTools = registry.getToolsByRole("teacher").stream()
                .map(ToolDefinition::name).toList();
        List<String> adminTools = registry.getToolsByRole("admin").stream()
                .map(ToolDefinition::name).toList();

        for (Expected expected : MIGRATED) {
            assertTrue(adminTools.contains(expected.name()),
                    "admin 工具列表里应包含 " + expected.name());
            assertFalse(studentTools.contains(expected.name()),
                    "管理员工具 " + expected.name() + " 不得出现在学生工具列表里");
            assertFalse(teacherTools.contains(expected.name()),
                    "管理员工具 " + expected.name() + " 不得出现在教师工具列表里");
            assertFalse(registry.isAllowedForRole("student", expected.name()),
                    "学生不该被允许调用 " + expected.name());
            assertFalse(registry.isAllowedForRole("teacher", expected.name()),
                    "教师不该被允许调用 " + expected.name());
            assertNull(registry.getTool("student", expected.name()),
                    "学生角色下不该能查到 " + expected.name() + " 的定义");
            assertNull(registry.getTool("teacher", expected.name()),
                    "教师角色下不该能查到 " + expected.name() + " 的定义");
        }
    }

    // ---------------------------------------------------------------- 业务数据

    /** 统计工具的数字必须与各 mapper 的真实计数一致（不是"有个字段"就算过） */
    @Test
    void statisticsToolReturnsTheRealCounts() throws Exception {
        Map<String, Object> stats = runJson("get_statistics", Map.of());

        assertEquals(sysUserMapper.countStudent(), ((Number) stats.get("totalStudents")).intValue(),
                "totalStudents 应与 sys_user 里学生数一致，实际=" + stats);
        assertEquals(sysUserMapper.countTeacher(), ((Number) stats.get("totalTeachers")).intValue(),
                "totalTeachers 应与教师数一致，实际=" + stats);
        assertEquals(sysUserMapper.countCourse(), ((Number) stats.get("totalCourses")).intValue(),
                "totalCourses 应与课程数一致，实际=" + stats);
        assertEquals(sysUserMapper.countClazz(), ((Number) stats.get("totalClasses")).intValue(),
                "totalClasses 应与班级数一致，实际=" + stats);
    }

    /** 学生列表：关键词过滤真的生效；不带筛选时返回全部（并在超过上限时截断到 50 条） */
    @Test
    void studentListFiltersByKeywordAndAppliesTheRowCap() throws Exception {
        List<Map<String, Object>> all = objectMapper.readValue(run("list_students", Map.of()),
                new TypeReference<>() {
                });
        int total = studentMapper.list(null, null, null, null, null).size();
        assertEquals(Math.min(50, total), all.size(),
                "不带筛选时应返回全部学生（上限 50），实际=" + all.size() + "，库里有 " + total);

        List<Map<String, Object>> filtered = objectMapper.readValue(
                run("list_students", Map.of("studentId", "2023001")), new TypeReference<>() {
                });
        assertTrue(filtered.size() >= 1, "种子数据里有学号 2023001，按学号过滤应能查到");
        assertTrue(filtered.stream().allMatch(s -> String.valueOf(s.get("studentId")).contains("2023001")),
                "过滤结果必须都匹配学号关键词，实际=" + filtered);
    }

    /** 课程列表：按课程名关键词过滤（种子数据里有 Java 程序设计） */
    @Test
    void courseListFiltersByName() throws Exception {
        List<Map<String, Object>> courses = objectMapper.readValue(
                run("list_courses", Map.of("courseName", "Java")), new TypeReference<>() {
                });
        assertTrue(courses.size() >= 1, "种子数据里应有名字含 Java 的课程，实际=" + courses);
        assertTrue(courses.stream()
                        .allMatch(c -> String.valueOf(c.get("courseName")).contains("Java")),
                "过滤结果必须都匹配关键词，实际=" + courses);
        assertTrue(courses.stream().allMatch(c -> c.get("courseCode") != null && c.get("term") != null),
                "课程行应带出课程代码与学期（模型要靠它们回答），实际=" + courses);
    }

    /** 学院列表：条数与 mapper 一致，且每行都有学院名 */
    @Test
    void collegeListMatchesTheMapper() throws Exception {
        List<Map<String, Object>> colleges = objectMapper.readValue(run("list_colleges", Map.of()),
                new TypeReference<>() {
                });
        List<College> fromMapper = collegeMapper.list(null);
        assertEquals(fromMapper.size(), colleges.size(), "学院条数应与 mapper 一致");
        assertTrue(colleges.stream().allMatch(c -> c.get("collegeName") != null
                        && !String.valueOf(c.get("collegeName")).isBlank()),
                "每行都应带学院名，实际=" + colleges);
    }

    /** 专业/班级/用户列表：条数与 mapper 一致（同一批迁移，形状与数量都不能变） */
    @Test
    void remainingListToolsMatchTheirMappers() throws Exception {
        List<Map<String, Object>> majors = objectMapper.readValue(run("list_majors", Map.of()),
                new TypeReference<>() {
                });
        assertEquals(majorMapper.list(null, null).size(), majors.size(), "专业条数应与 mapper 一致");

        List<Map<String, Object>> classes = objectMapper.readValue(run("list_classes", Map.of()),
                new TypeReference<>() {
                });
        assertEquals(clazzMapper.list(null, null, null, null).size(), classes.size(), "班级条数应与 mapper 一致");

        List<Map<String, Object>> users = objectMapper.readValue(run("list_users", Map.of()),
                new TypeReference<>() {
                });
        List<SysUser> allUsers = sysUserMapper.list(null, null);
        assertEquals(Math.min(50, allUsers.size()), users.size(),
                "不带筛选时应返回全部用户（上限 50）");

        // 按角色筛选真的生效（这条也顺带证明角色筛选值没被迁移改坏）
        List<Map<String, Object>> teachersOnly = objectMapper.readValue(
                run("list_users", Map.of("role", "teacher")), new TypeReference<>() {
                });
        assertTrue(teachersOnly.size() >= 1, "种子数据里有教师账号");
        assertTrue(teachersOnly.stream().allMatch(u -> "teacher".equals(u.get("role"))),
                "按 teacher 筛选后不应混入其它角色，实际=" + teachersOnly);
    }

    /**
     * 审批工具：**真的调用同一个 service**，且审批人取自 ToolContext（当前登录账号）。
     *
     * <p>造一条 PENDING 申请 → 用声明式工具审批 → 断言申请状态、审批人、以及真的生成了课程。
     * 事务回滚保证可反复跑。
     */
    @Test
    void approveToolApprovesAPendingApplicationAndTakesReviewerFromTheToken() throws Exception {
        CourseApply apply = new CourseApply();
        apply.setTeacherId("10001");               // 种子教师，已归属学院
        apply.setCourseCode("CS900");              // 测试专用课程代码
        apply.setCourseName("声明式迁移测试课程");
        apply.setTerm("2024-2025-1");
        apply.setCollegeId(1);
        apply.setCredit(new BigDecimal("2.0"));
        apply.setClassHour(32);
        apply.setMaxStudent(40);
        apply.setStatus(CourseApply.STATUS_PENDING);
        courseApplyMapper.add(apply);
        assertNotNull(apply.getId(), "插入 PENDING 申请后应拿到自增 id");

        Map<String, Object> result = runJson("approve_course_apply", Map.of("applyId", apply.getId()));

        assertFalse(result.containsKey("error"), "正常审批不该返回 error，实际=" + result);
        assertEquals(CourseApply.STATUS_APPROVED, result.get("status"), "申请状态应为已通过");
        assertNotNull(result.get("createdCourseId"), "通过后应生成课程并回传 courseId，实际=" + result);
        assertEquals("CS900", result.get("courseCode"));

        CourseApply after = courseApplyMapper.selectById(apply.getId());
        assertEquals(CourseApply.STATUS_APPROVED, after.getStatus());
        assertEquals(ADMIN, after.getReviewer(),
                "审批人必须是当前登录账号（来自 ToolContext），实际=" + after.getReviewer());

        Course created = courseMapper.selectCourseById(((Number) result.get("createdCourseId")).intValue());
        assertNotNull(created, "应真的生成课程记录");
        assertEquals("CS900", created.getCourseCode(), "课程代码沿用申请里的（培养计划要靠它关联）");
        assertEquals("10001", created.getTeacherId());
    }

    /** 业务失败也要走 JSON（而不是抛异常）：审批不存在的申请返回可读 error */
    @Test
    void approveToolReturnsReadableErrorForUnknownApplication() throws Exception {
        Map<String, Object> result = runJson("approve_course_apply", Map.of("applyId", 999999));
        assertTrue(result.containsKey("error"),
                "审批不存在的申请应返回 {\"error\":...} 让人/模型能解释失败原因，实际=" + result);
        assertTrue(String.valueOf(result.get("error")).contains("不存在"),
                "错误信息应说明申请不存在，实际=" + result.get("error"));
    }
}
