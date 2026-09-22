package duyell.ai.tool.declarative;

import com.duyell.Score;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import duyell.mapper.ScoreMapper;
import duyell.service.CourseApplyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 教师工具**声明式迁移**的契约测试（M2 计划 1.3 批量迁移）。
 *
 * <p>迁移教师工具的风险不在"能不能跑"，而在**换写法时悄悄丢掉东西**。本测试把三类"丢了不会报错、
 * 只会让模型行为变坏"的东西全部钉住：
 * <ol>
 *   <li><b>展示名与风险等级</b>：{@code displayName} 决定确认卡片上给人看的名字，
 *       {@code riskLevel} 决定要不要弹卡片——标错就是 HITL 失效（写操作静默执行）或误弹；</li>
 *   <li><b>参数 Schema 形状</b>：属性名集合、类型、{@code required} 集合必须与手写版一致。
 *       少一个 required，模型就能不带课程ID触发写操作；多一个，模型会去问本不该问的参数；</li>
 *   <li><b>取值约束</b>（{@code minimum}/{@code maximum}）：框架的 {@code @ToolParam} 表达不了，
 *       必须由 {@link ParamConstraint} 补上——丢了它，模型就看不到"成绩只能在 0~100"
 *       （{@code .dsh/verify-privacy.ps1} 里那条 "enter_score usualScore is bounded 0..100"
 *       就是专门盯这个的）。期望值在这里**硬编码**，作为防漂移的网。</li>
 * </ol>
 *
 * <p>再加两类端到端断言：
 * <ul>
 *   <li><b>归属校验</b>：教师 10001 对**别人**的课程（10004 的课程 5）必须被拒且给出可读原因；</li>
 *   <li><b>业务数据</b>：课程名单/成绩总分/评教条数等真实值，而不是"非空"。
 *       （2026-09-22 那个"学生问选了什么课、助手答没选任何课"的 bug 就是只断言"非空"查不出来的。）</li>
 * </ul>
 *
 * <p>用真实库 + 回滚事务隔离，可反复跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class TeacherDeclarativeToolsTest {

    private static final String TEACHER = "10001";
    private static final String OTHER_TEACHER = "10004";
    /** 种子数据：10001 拥有课程 1/4/10；10004 拥有课程 5 */
    private static final int OWN_COURSE = 1;
    private static final int OTHER_COURSE = 5;

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScoreMapper scoreMapper;

    @Autowired
    private CourseApplyService courseApplyService;

    /**
     * 期望的一个参数：类型 + 取值约束。
     *
     * <p>{@code min}/{@code max} 为 {@code null} 表示"**期望这一端不存在**"
     * （手写版就没有这个约束，迁移也不能擅自加）；非 null 则要求**逐值相等**。
     */
    private record ParamSpec(String type, Long min, Long max) {
    }

    /** 只有类型，两端都不限制 */
    private static ParamSpec of(String type) {
        return new ParamSpec(type, null, null);
    }

    /** 有下界、无上界（手写版写成 {@code "minimum": 0} 的那种） */
    private static ParamSpec ofMin(String type, long min) {
        return new ParamSpec(type, min, null);
    }

    /** 上下界都有 */
    private static ParamSpec ofRange(String type, long min, long max) {
        return new ParamSpec(type, min, max);
    }

    private ToolDefinition tool(String name) {
        ToolDefinition def = registry.getTool("teacher", name);
        assertNotNull(def, "teacher 角色下应有工具 " + name);
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

    private Long numeric(Object schema, String key) {
        if (!(schema instanceof Map)) {
            return null;
        }
        Object value = ((Map<String, Object>) schema).get(key);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    /** 逐个核对：属性名集合、类型、required、以及 minimum/maximum（期望值硬编码） */
    private void assertSchema(String toolName, List<String> expectedRequired, Map<String, ParamSpec> expected) {
        ToolDefinition def = tool(toolName);
        Map<String, Object> props = properties(def);

        assertEquals(new HashSet<>(expected.keySet()), new HashSet<>(props.keySet()),
                toolName + " 的参数属性名集合必须与手写版一致");
        assertEquals(new HashSet<>(expectedRequired), new HashSet<>(required(def)),
                toolName + " 的 required 集合必须与手写版一致");

        for (Map.Entry<String, ParamSpec> entry : expected.entrySet()) {
            String param = entry.getKey();
            ParamSpec spec = entry.getValue();
            Object schema = props.get(param);
            assertNotNull(schema, toolName + "." + param + " 缺少 Schema");

            Map<String, Object> schemaMap = (Map<String, Object>) schema;
            assertEquals(spec.type(), String.valueOf(schemaMap.get("type")),
                    toolName + "." + param + " 的类型必须与手写版一致，实际=" + schemaMap);
            assertEquals(spec.min(), numeric(schema, "minimum"),
                    toolName + "." + param + " 的 minimum 必须与手写版一致（丢了模型就看不到取值范围），实际=" + schemaMap);
            assertEquals(spec.max(), numeric(schema, "maximum"),
                    toolName + "." + param + " 的 maximum 必须与手写版一致，实际=" + schemaMap);
            assertTrue(String.valueOf(schemaMap.get("description")).length() >= 2,
                    toolName + "." + param + " 应有可读描述，实际=" + schemaMap);
        }
    }

    // ==================================================================================
    // 一、Schema 契约（形状 + 取值约束）
    // ==================================================================================

    @Test
    void getMyCoursesSchemaIsUnchanged() {
        assertSchema("get_my_courses", List.of(), Map.of());
        assertEquals("我的授课课程", tool("get_my_courses").displayName());
    }

    @Test
    void getCourseStudentsSchemaIsUnchanged() {
        // 手写版对 courseId 只有类型与描述，没有下界——这里也不能"顺手补一个 minimum"
        assertSchema("get_course_students", List.of("courseId"),
                Map.of("courseId", of("integer")));
        assertEquals("课程学生名单", tool("get_course_students").displayName());
        assertEquals(RiskLevel.READ_ONLY, tool("get_course_students").riskLevel());
        assertFalse(tool("get_course_students").requiresConfirmation());
    }

    /** 成绩范围 0~100：迁移最容易被咬红的一条（verify-privacy.ps1 专门盯它） */
    @Test
    void enterScoreSchemaKeepsTheScoreRange() {
        assertSchema("enter_score", List.of("courseId", "studentId"),
                Map.of(
                        "courseId", of("integer"),
                        "studentId", of("string"),
                        "usualScore", ofRange("number", 0, 100),
                        "examScore", ofRange("number", 0, 100)));
        assertEquals("录入成绩", tool("enter_score").displayName());
        assertEquals(RiskLevel.DANGEROUS, tool("enter_score").riskLevel(),
                "录成绩是写操作，必须弹确认卡片");
        assertTrue(tool("enter_score").requiresConfirmation());
    }

    @Test
    void updateScoreSchemaKeepsTheScoreRange() {
        assertSchema("update_score", List.of("id"),
                Map.of(
                        "id", of("integer"),
                        "usualScore", ofRange("number", 0, 100),
                        "examScore", ofRange("number", 0, 100)));
        assertEquals("修改成绩", tool("update_score").displayName());
        assertEquals(RiskLevel.DANGEROUS, tool("update_score").riskLevel());
        assertTrue(tool("update_score").requiresConfirmation());
    }

    @Test
    void getMyEvaluationsSchemaIsUnchanged() {
        assertSchema("get_my_evaluations", List.of(), Map.of());
        assertEquals("学生评价", tool("get_my_evaluations").displayName());
        assertEquals(RiskLevel.READ_ONLY, tool("get_my_evaluations").riskLevel());
    }

    @Test
    void submitCourseApplySchemaKeepsAllConstraints() {
        assertSchema("submit_course_apply",
                List.of("courseCode", "courseName", "term", "credit", "classHour", "maxStudent"),
                Map.ofEntries(
                        Map.entry("courseCode", of("string")),
                        Map.entry("courseName", of("string")),
                        Map.entry("term", of("string")),
                        Map.entry("credit", ofMin("number", 0)),
                        Map.entry("classHour", ofMin("integer", 0)),
                        Map.entry("maxStudent", ofMin("integer", 1)),
                        Map.entry("expectedWeekday", ofRange("integer", 1, 7)),
                        Map.entry("expectedStartPeriod", ofRange("integer", 1, 10)),
                        Map.entry("expectedEndPeriod", ofRange("integer", 1, 10)),
                        Map.entry("expectedStartWeek", ofRange("integer", 1, 53)),
                        Map.entry("expectedEndWeek", ofRange("integer", 1, 53)),
                        // 手写版 preferRoomId 没有下界：迁移不能擅自收紧模型可见的取值域
                        Map.entry("preferRoomId", of("integer"))));
        assertEquals("提交开课申请", tool("submit_course_apply").displayName());
        assertEquals(RiskLevel.DANGEROUS, tool("submit_course_apply").riskLevel());
        assertTrue(tool("submit_course_apply").requiresConfirmation());
    }

    @Test
    void applyClassTimeSchemaKeepsAllConstraints() {
        assertSchema("apply_class_time",
                List.of("courseId", "weekday", "startPeriod", "endPeriod", "startWeek", "endWeek"),
                Map.of(
                        "courseId", ofMin("integer", 1),
                        "weekday", ofRange("integer", 1, 7),
                        "startPeriod", ofRange("integer", 1, 10),
                        "endPeriod", ofRange("integer", 1, 10),
                        "startWeek", ofRange("integer", 1, 53),
                        "endWeek", ofRange("integer", 1, 53),
                        "roomId", ofMin("integer", 1)));
        assertEquals("申请排课", tool("apply_class_time").displayName());
        assertEquals(RiskLevel.DANGEROUS, tool("apply_class_time").riskLevel());
        assertTrue(tool("apply_class_time").requiresConfirmation());
    }

    /**
     * 调用者身份**不在**参数 Schema 里。
     *
     * <p>ToolContext 只是方法参数，不是工具参数：它若漏进 Schema，模型就能填别人的工号，
     * 从而"替别人改成绩/排课"。
     */
    @Test
    void callerIdentityNeverAppearsInAnyTeacherSchema() {
        for (String name : List.of("get_my_courses", "get_course_students", "enter_score",
                "update_score", "get_my_evaluations", "submit_course_apply", "apply_class_time")) {
            String schema = String.valueOf(tool(name).parameters());
            assertFalse(schema.contains(DeclarativeToolContext.KEY_USER_ID),
                    name + " 的参数 Schema 不得出现 userId：" + schema);
            assertFalse(schema.toLowerCase().contains("toolcontext"),
                    name + " 的参数 Schema 不得出现 ToolContext：" + schema);
        }
    }

    /** 教师工具集合必须与手写版一一对应：不多、不少、不改名 */
    @Test
    void teacherToolSurfaceIsUnchanged() {
        Set<String> names = new HashSet<>(registry.getToolsByRole("teacher").stream()
                .map(ToolDefinition::name).toList());
        assertEquals(Set.of("get_my_courses", "get_course_students", "enter_score", "update_score",
                        "get_my_evaluations", "submit_course_apply", "apply_class_time"),
                names, "教师工具面必须保持不变（迁移只换写法，不增删工具）");
    }

    // ==================================================================================
    // 二、归属校验（只能操作本人所授课程）
    // ==================================================================================

    private ToolExecutionResult run(String toolName, Map<String, Object> args) {
        return registry.executeForRole(tool(toolName), "teacher", args, TEACHER);
    }

    @Test
    void courseStudentsRejectsOtherTeachersCourse() {
        ToolExecutionResult mine = run("get_course_students", Map.of("courseId", OWN_COURSE));
        assertTrue(mine.isSuccess(), "本人课程应可查询，实际 " + mine.status());
        assertTrue(mine.payload().contains("2023001"), "课程 1 的名单应含 2023001：" + mine.payload());

        ToolExecutionResult others = run("get_course_students", Map.of("courseId", OTHER_COURSE));
        assertTrue(others.isSuccess(), "被拒也应是一次'成功返回可读原因'，而不是抛异常");
        assertTrue(others.payload().contains("无权限"),
                "查他人课程必须被拒并说明原因，实际=" + others.payload());
        assertFalse(others.payload().contains("2023005"),
                "被拒时不得泄露他人课程的名单：" + others.payload());
    }

    @Test
    void enterScoreRejectsOtherTeachersCourse() {
        // 记录调用前的状态：该组合**可能本来就没有成绩记录**
        // （不能假定它一定有——夹具假设导致的 NPE 看起来像产品 bug）
        Score before = scoreMapper.select(OTHER_COURSE, 2023001);
        ToolExecutionResult others = run("enter_score",
                Map.of("courseId", OTHER_COURSE, "studentId", "2023001", "usualScore", 90, "examScore", 90));
        assertTrue(others.payload().contains("无权限"),
                "为他人课程录成绩必须被拒，实际=" + others.payload());
        Score after = scoreMapper.select(OTHER_COURSE, 2023001);
        if (before == null) {
            assertNull(after, "被拒后不得创建任何成绩记录");
        } else {
            assertEquals(before.getUsualScore(), after.getUsualScore(), "被拒后不得改动任何数据");
            assertEquals(before.getExamScore(), after.getExamScore(), "被拒后不得改动任何数据");
        }
    }

    @Test
    void updateScoreRejectsOtherTeachersCourse() {
        // 成绩 12 = 课程 5（10004 的课）+ 学生 2023005
        ToolExecutionResult others = run("update_score", Map.of("id", 12, "examScore", 100));
        assertTrue(others.payload().contains("无权限"),
                "改他人课程的成绩必须被拒，实际=" + others.payload());
        assertEquals(92.0, scoreMapper.select(OTHER_COURSE, 2023005).getExamScore().doubleValue(),
                "被拒后成绩必须原封不动");
    }

    @Test
    void applyClassTimeRejectsOtherTeachersCourse() {
        ToolExecutionResult others = run("apply_class_time", Map.of(
                "courseId", OTHER_COURSE, "weekday", 3, "startPeriod", 5, "endPeriod", 6,
                "startWeek", 1, "endWeek", 16));
        assertTrue(others.payload().contains("只能为自己的课程"),
                "为他人课程排课必须被拒并说明原因，实际=" + others.payload());
    }

    // ==================================================================================
    // 三、业务数据（真实值，而不是"非空"）
    // ==================================================================================

    @Test
    void courseStudentsReturnsTheRealRoster() throws Exception {
        ToolExecutionResult result = run("get_course_students", Map.of("courseId", OWN_COURSE));
        List<Map<String, Object>> students = objectMapper.readValue(result.payload(),
                new TypeReference<>() {
                });
        // 种子数据：课程 1（CS101）的选课学生是 2023001/2023002/2023003/2023007/2024001
        Set<String> ids = new HashSet<>(students.stream()
                .map(s -> String.valueOf(s.get("studentId"))).toList());
        assertEquals(Set.of("2023001", "2023002", "2023003", "2023007", "2024001"), ids,
                "课程 1 的名单必须是这 5 个学号，实际=" + ids);
    }

    @Test
    void enterScoreComputesTheTotalThroughScoreService() throws Exception {
        // 课程 1 + 2023007：种子数据里该生此课程没有成绩
        assertNull(scoreMapper.select(OWN_COURSE, 2023007), "前置条件：2023007 在课程 1 上应无成绩");

        ToolExecutionResult result = run("enter_score", Map.of(
                "courseId", OWN_COURSE, "studentId", "2023007", "usualScore", 80, "examScore", 90));
        assertTrue(result.isSuccess(), "执行应成功，实际 " + result.status());

        // 总成绩 = 80×0.4 + 90×0.6 = 86.000（精度与权重只有 ScoreService 那一处实现）
        Map<String, Object> payload = objectMapper.readValue(result.payload(), new TypeReference<>() {
        });
        assertEquals("成绩录入成功", payload.get("message"));
        assertEquals(0, new java.math.BigDecimal(String.valueOf(payload.get("totalScore")))
                .compareTo(new java.math.BigDecimal("86.000")), "总成绩应为 86.000，实际=" + payload);

        // 落库且 passed 已派生（绕过 ScoreService 曾导致 passed 为 NULL）
        var saved = scoreMapper.select(OWN_COURSE, 2023007);
        assertNotNull(saved, "成绩必须真的落库");
        assertEquals(1, saved.getPassed().intValue(), "passed 必须由 ScoreService 派生（86 分应为通过）");
    }

    @Test
    void enterScoreRefusesDuplicateAndPointsToUpdate() throws Exception {
        // 课程 1 + 2023001 在种子里已有成绩
        ToolExecutionResult result = run("enter_score", Map.of(
                "courseId", OWN_COURSE, "studentId", "2023001", "usualScore", 100, "examScore", 100));
        Map<String, Object> payload = objectMapper.readValue(result.payload(), new TypeReference<>() {
        });
        assertEquals("该学生此课程已有成绩记录，请使用 update_score 修改", payload.get("message"),
                "重复录入必须给出与手写版一致的提示，实际=" + payload);
        assertEquals(85.0, scoreMapper.select(OWN_COURSE, 2023001).getUsualScore().doubleValue(),
                "提示重复时不得改动原成绩");
    }

    @Test
    void updateScoreRecomputesTotalAndKeepsTheOtherTerm() throws Exception {
        // 成绩 8 = 课程 1 + 2023003，种子值 70/78（总分 74.8）
        ToolExecutionResult result = run("update_score", Map.of("id", 8, "examScore", 90));
        Map<String, Object> payload = objectMapper.readValue(result.payload(), new TypeReference<>() {
        });
        assertEquals("成绩修改成功", payload.get("message"));
        // 只改考试分：70×0.4 + 90×0.6 = 82.000；平时分必须保留（不能被清零）
        assertEquals(0, new java.math.BigDecimal(String.valueOf(payload.get("totalScore")))
                .compareTo(new java.math.BigDecimal("82.000")), "总成绩应为 82.000，实际=" + payload);
        var updated = scoreMapper.select(OWN_COURSE, 2023003);
        assertEquals(70.0, updated.getUsualScore().doubleValue(), "未传的平时分必须沿用原值");
        assertEquals(1, updated.getPassed().intValue());
    }

    /** 评教必须是匿名的：服务端剥掉提交人，工具不得把它带出来 */
    @Test
    void evaluationsAreAnonymousAndReal() throws Exception {
        ToolExecutionResult result = run("get_my_evaluations", Map.of());
        List<Map<String, Object>> rows = objectMapper.readValue(result.payload(),
                new TypeReference<>() {
                });
        // 种子数据：教师 10001 收到 2 条评价（课程 1 与课程 4，均为 5 分）
        assertEquals(2, rows.size(), "10001 应收到 2 条评价，实际=" + rows);
        for (Map<String, Object> row : rows) {
            assertNull(row.get("studentId"), "匿名评教不得带出提交人学号：" + row);
            assertNull(row.get("studentName"), "匿名评教不得带出提交人姓名：" + row);
        }
        assertTrue(rows.stream().allMatch(r -> Integer.valueOf(5).equals(r.get("score"))),
                "评价分值应是种子里的 5 分，实际=" + rows);
    }

    @Test
    void submitCourseApplyPersistsPendingApplication() throws Exception {
        ToolExecutionResult result = run("submit_course_apply", Map.of(
                "courseCode", "CS901", "courseName", "声明式迁移验证课", "term", "2024-2025-2",
                "credit", 3.0, "classHour", 48, "maxStudent", 50));
        assertTrue(result.isSuccess(), "执行应成功，实际 " + result.status());

        Map<String, Object> payload = objectMapper.readValue(result.payload(), new TypeReference<>() {
        });
        assertEquals("PENDING", payload.get("status"));
        assertEquals("待审批", payload.get("statusText"));
        assertEquals("CS901", payload.get("courseCode"));
        assertEquals("2024-2025-2", payload.get("term"));
        assertNotNull(payload.get("id"), "应返回申请单号");

        // 真的落库了，而且申请人取的是当前登录教师（不接受模型指定）
        assertTrue(courseApplyService.listMine(TEACHER).stream()
                        .anyMatch(a -> "CS901".equals(a.getCourseCode())),
                "申请单应出现在该教师的申请列表里");
    }

    @Test
    void submitCourseApplyRejectsPartialExpectedTime() throws Exception {
        // 只给 expectedWeekday：服务端只在 weekday 非空时校验区间，会静默接受残缺的期望时间，
        // 因此这道"要么全给要么全不给"的检查必须留在工具侧
        ToolExecutionResult result = run("submit_course_apply", Map.of(
                "courseCode", "CS902", "courseName", "残缺期望时间", "term", "2024-2025-2",
                "credit", 2.0, "classHour", 32, "maxStudent", 40,
                "expectedWeekday", 3));
        assertTrue(result.payload().contains("要么全部省略"),
                "只给一半期望时间必须被拒并说明原因，实际=" + result.payload());
        assertTrue(courseApplyService.listMine(TEACHER).stream()
                        .noneMatch(a -> "CS902".equals(a.getCourseCode())),
                "被拒时不得落库");
    }

    @Test
    void applyClassTimePersistsPendingApplicationForOwnCourse() throws Exception {
        ToolExecutionResult result = run("apply_class_time", Map.of(
                "courseId", OWN_COURSE, "weekday", 3, "startPeriod", 5, "endPeriod", 6,
                "startWeek", 1, "endWeek", 16));
        assertTrue(result.isSuccess(), "执行应成功，实际 " + result.status());

        Map<String, Object> payload = objectMapper.readValue(result.payload(), new TypeReference<>() {
        });
        assertEquals("PENDING", payload.get("status"));
        assertEquals("待审批", payload.get("statusText"));
        assertEquals(OWN_COURSE, ((Number) payload.get("courseId")).intValue());
        assertEquals(3, ((Number) payload.get("weekday")).intValue());
        assertNotNull(payload.get("id"), "应返回申请单号");
        assertNotNull(payload.get("message"), "必须给出可转述的提示文本");
    }
}

