package duyell.ai.tool.declarative;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolArgumentValidator;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学生工具**声明式迁移**的契约测试（M2 计划 1.3 批量迁移）。
 *
 * <p>它要回答的问题不是"注解解析得对不对"，而是三件迁移最容易弄坏的事：
 * <ol>
 *   <li><b>元数据漂移</b>：展示名（确认卡片上给人看的名字）与风险等级
 *       —— 风险等级标错等于写操作不再需要人工确认，这是安全事故而不是 bug；</li>
 *   <li><b>参数形状漂移</b>：{@code required} 与 {@code properties} 的键集合必须与手写版一致。
 *       少了 required 项，模型就能不带课程ID触发写操作；
 *       多出 {@code userId}/{@code toolContext}，模型就能"替别人查/选课"；</li>
 *   <li><b>数据错位</b>：工具能跑通但查了错的人/错的表。历史上真实发生过
 *       （"学生问'我选了什么课'、助手答'你没选任何课'"），只断言"非空"抓不到这类错误，
 *       因此对只读工具断言到**具体业务数据**（课程代码、绩点、方案条目）。</li>
 * </ol>
 *
 * <p>期望值全部**写在断言里**（而不是从手写 Registrar 现算）：这些字面量就是"迁移不许改的契约"，
 * 手写版哪天改了字段名而声明式版没跟上，这里会红。
 *
 * <p>用真实库（种子数据：学生 2023001）+ 回滚事务隔离，可反复跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class StudentDeclarativeToolsTest {

    private static final String STUDENT = "2023001";
    private static final String ROLE = "student";

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ToolArgumentValidator toolArgumentValidator;

    @Autowired
    private ObjectMapper objectMapper;

    /** 一个工具的"迁移契约"：展示名、风险等级、必填参数、参数键集合 */
    private record Contract(String name, String displayName, RiskLevel riskLevel,
                            List<String> required, Set<String> properties) {
    }

    /**
     * 全部 17 个学生工具的契约（手写版为准，逐字抄来）。
     *
     * <p>顺序与 {@code StudentToolRegistrar} 的注册顺序一致，便于对照阅读。
     */
    private static final List<Contract> CONTRACTS = List.of(
            new Contract("get_my_courses", "我的已选课程", RiskLevel.READ_ONLY, List.of(), Set.of()),
            new Contract("get_my_scores", "我的成绩", RiskLevel.READ_ONLY, List.of(), Set.of()),
            new Contract("select_course", "选课", RiskLevel.DANGEROUS, List.of("courseId"), Set.of("courseId")),
            new Contract("drop_course", "退课", RiskLevel.WRITE, List.of("courseId"), Set.of("courseId")),
            new Contract("get_course_list", "可选课程列表", RiskLevel.READ_ONLY, List.of(), Set.of("courseName")),
            new Contract("evaluate_teacher", "教学评价", RiskLevel.DANGEROUS,
                    List.of("courseId", "teacherId", "score"),
                    Set.of("courseId", "teacherId", "score", "content")),
            new Contract("check_evaluation", "评价状态检查", RiskLevel.READ_ONLY,
                    List.of("courseId"), Set.of("courseId")),
            new Contract("get_my_evaluations", "我的评价", RiskLevel.READ_ONLY, List.of(), Set.of()),
            new Contract("get_my_training_plan", "我的培养方案", RiskLevel.READ_ONLY, List.of(), Set.of()),
            new Contract("audit_my_graduation", "毕业学分审核", RiskLevel.READ_ONLY, List.of(), Set.of()),
            new Contract("get_my_gpa", "我的绩点与排名", RiskLevel.READ_ONLY, List.of(), Set.of("term")),
            new Contract("recommend_courses", "推荐可选课程", RiskLevel.READ_ONLY, List.of(), Set.of("term")),
            new Contract("get_my_exams", "我的考试安排", RiskLevel.READ_ONLY, List.of(),
                    Set.of("term", "upcoming")),
            new Contract("get_selection_status", "选课开放状态", RiskLevel.READ_ONLY, List.of(), Set.of("term")),
            new Contract("list_my_class_times", "我的课表", RiskLevel.READ_ONLY, List.of(), Set.of("term")),
            new Contract("get_my_academic_warning", "我的学业预警", RiskLevel.READ_ONLY, List.of(), Set.of()),
            new Contract("check_time_conflict", "检查上课时间冲突", RiskLevel.READ_ONLY, List.of(),
                    Set.of("courseId", "courseCode", "courseName"))
    );

    private ToolDefinition tool(String name) {
        ToolDefinition def = registry.getTool(ROLE, name);
        assertNotNull(def, "student 角色下应有工具 " + name);
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
        // 框架对"没有必填参数"的工具可能省略 required 键，也可能给空数组——两种都算"没有必填项"
        return req instanceof List ? (List<String>) req : List.of();
    }

    /** 单个参数的 Schema（取自 {@code properties.<name>}） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> property(ToolDefinition def, String name) {
        Object prop = properties(def).get(name);
        assertNotNull(prop, def.name() + " 应声明参数 " + name);
        return (Map<String, Object>) prop;
    }

    private static int intOf(Object value) {
        assertNotNull(value, "约束值不应为 null");
        return ((Number) value).intValue();
    }

    private Object execute(String name, Map<String, Object> args, String userId) {
        ToolDefinition def = tool(name);
        ToolExecutionResult result = registry.executeForRole(def, ROLE, args, userId);
        assertTrue(result.isSuccess(), name + " 应以业务载荷返回，实际 " + result.status()
                + " / " + result.errorDetail());
        assertNotNull(result.payload(), name + " 的载荷不应为 null");
        return result.payload();
    }

    private Map<String, Object> payloadAsMap(String name, Map<String, Object> args, String userId) throws Exception {
        return objectMapper.readValue(String.valueOf(execute(name, args, userId)),
                new TypeReference<>() {
                });
    }

    // ==================================================================================
    // 一、逐个工具：展示名 / 风险等级 / 必填 / 参数键集合（防漂移）
    // ==================================================================================

    @Test
    void everyStudentToolKeepsItsHandWrittenContract() {
        for (Contract expected : CONTRACTS) {
            ToolDefinition def = tool(expected.name());

            assertEquals(expected.displayName(), def.displayName(),
                    expected.name() + " 的中文展示名必须与手写版一致（它出现在确认卡片与审计里）");
            assertEquals(expected.riskLevel(), def.riskLevel(),
                    expected.name() + " 的风险等级必须与手写版一致");

            // requiresConfirmation() 是 HITL 的开关：它只应由 DANGEROUS 触发
            assertEquals(expected.riskLevel() == RiskLevel.DANGEROUS, def.requiresConfirmation(),
                    expected.name() + " 的确认卡片开关与风险等级不一致");

            // required 在 JSON Schema 里是无序集合，比较用 Set：
            // 框架按形参顺序写数组，但"顺序"不是契约，钉顺序会让测试与框架实现细节耦合
            assertEquals(new java.util.TreeSet<>(expected.required()),
                    new java.util.TreeSet<>(required(def)),
                    expected.name() + " 的必填参数集合必须与手写版一致");
            assertEquals(expected.properties(), new LinkedHashSet<>(properties(def).keySet()),
                    expected.name() + " 的参数键集合必须与手写版一致（多一个少一个都是契约变更）");
        }
    }

    /** 危险写操作必须是 DANGEROUS —— 单独再钉一次，避免将来"顺手"改成 WRITE */
    @Test
    void dangerousWritesStillRequireConfirmation() {
        for (String name : List.of("select_course", "evaluate_teacher")) {
            ToolDefinition def = tool(name);
            assertEquals(RiskLevel.DANGEROUS, def.riskLevel(), name + " 是写操作，必须人工确认");
            assertTrue(def.requiresConfirmation(), name + " 必须弹确认卡片");
        }
        // 退课是可逆写操作，手写版标 WRITE（不弹卡片）——迁移不得擅自升级或降级
        assertEquals(RiskLevel.WRITE, tool("drop_course").riskLevel());
        assertFalse(tool("drop_course").requiresConfirmation());
    }

    /** 调用者身份绝不能出现在参数 Schema 里（否则模型能改成别人的学号） */
    @Test
    void callerIdentityNeverLeaksIntoAnySchema() {
        for (Contract expected : CONTRACTS) {
            String schema = String.valueOf(tool(expected.name()).parameters());
            assertFalse(schema.contains(DeclarativeToolContext.KEY_USER_ID),
                    expected.name() + " 的参数里不得出现 userId：" + schema);
            assertFalse(schema.toLowerCase().contains("toolcontext"),
                    expected.name() + " 的参数里不得出现 ToolContext：" + schema);
            assertFalse(schema.contains(ROLE + "\""),
                    expected.name() + " 的参数里不得出现角色：" + schema);
        }
    }

    /**
     * 参数取值约束必须出现在 Schema 里 —— 这是"模型看到的约束"。
     *
     * <p>框架的 {@code @ToolParam} 表达不了 enum/min/max，迁移时最容易**悄悄丢掉**：
     * 丢掉的后果不是报错，而是模型开始传越界值（例如给 5 星制评分填 100）。
     * 本项目的 {@code JsonSchemaToolArgumentValidator} 是真的按 Schema 拦的，
     * 所以这条断言同时守住"客户侧校验"与"模型可见的契约"。
     *
     * <p>学生角色里带数值约束的只有 {@code evaluate_teacher.score}（5 星制 1~5）；
     * 下面同时断言"其它工具没有多余的约束"，防止顺手给别的参数加上限制。
     */
    @Test
    void numericConstraintsAreCarriedIntoTheSchema() {
        Map<String, Object> scoreSchema = property(tool("evaluate_teacher"), "score");
        assertEquals(1, intOf(scoreSchema.get("minimum")), "评分下界必须是 1（5 星制）");
        assertEquals(5, intOf(scoreSchema.get("maximum")), "评分上界必须是 5（5 星制）");

        // 手写版学生工具里**没有**任何 enum 约束；迁移不得凭空增加或搬走约束
        for (Contract expected : CONTRACTS) {
            for (String property : expected.properties()) {
                Map<String, Object> schema = property(tool(expected.name()), property);
                boolean expectConstraint = "evaluate_teacher".equals(expected.name()) && "score".equals(property);
                if (!expectConstraint) {
                    assertFalse(schema.containsKey("minimum"),
                            expected.name() + "." + property + " 不该有 minimum：" + schema);
                    assertFalse(schema.containsKey("maximum"),
                            expected.name() + "." + property + " 不该有 maximum：" + schema);
                    assertFalse(schema.containsKey("enum"),
                            expected.name() + "." + property + " 不该有 enum：" + schema);
                }
            }
        }
    }

    /** 约束必须与校验器一致：越界值真的会被 {@code ToolArgumentValidator} 拒绝 */
    @Test
    void scoreConstraintIsEnforcedByTheSharedValidator() {
        ToolDefinition def = tool("evaluate_teacher");

        // 用生产上下文里的那个校验器 Bean（而不是自己 new 一个），确保测的是真实接线
        assertTrue(toolArgumentValidator.validate(def.parameters(),
                Map.of("courseId", 2, "teacherId", "10002", "score", 5)).valid(),
                "5 分是合法值（上界含 5）");
        assertFalse(toolArgumentValidator.validate(def.parameters(),
                Map.of("courseId", 2, "teacherId", "10002", "score", 6)).valid(),
                "6 分必须被校验器拒绝");
        assertFalse(toolArgumentValidator.validate(def.parameters(),
                Map.of("courseId", 2, "teacherId", "10002", "score", 0)).valid(),
                "0 分必须被校验器拒绝");
    }

    // ==================================================================================
    // 二、数据正确性：只读工具必须返回**这个学生本人的真实数据**
    // ==================================================================================

    /** 成绩：断言到具体课程与分数（种子数据 4 门已通过的成绩） */
    @Test
    void myScoresReturnsTheStudentsRealScores() throws Exception {
        List<Map<String, Object>> rows = objectMapper.readValue(
                String.valueOf(execute("get_my_scores", Map.of(), STUDENT)), new TypeReference<>() {
                });

        Map<String, Object> cs101 = rows.stream()
                .filter(r -> "CS101".equals(r.get("courseCode"))).findFirst().orElse(null);
        assertNotNull(cs101, "应返回该生 CS101 的成绩，实际=" + rows);
        assertEquals(0, new BigDecimal("88.000").compareTo(new BigDecimal(String.valueOf(cs101.get("totalScore")))),
                "CS101 总评应为 88 分，实际=" + cs101.get("totalScore"));
        assertEquals(1, cs101.get("passed"), "88 分应记为通过");
        assertEquals(STUDENT, cs101.get("studentId"), "只应返回本人成绩");

        // 4 门种子成绩都在（用 contains 而不是精确条数：其它脚本可能给该生补录成绩）
        Set<String> codes = new LinkedHashSet<>();
        rows.forEach(r -> codes.add(String.valueOf(r.get("courseCode"))));
        assertTrue(codes.containsAll(List.of("CS101", "CS102", "CS103", "CS104")),
                "应包含 4 门种子课程，实际=" + codes);
    }

    /** 绩点与排名：断言绩点数值、明细里的单科绩点、以及本人名次 */
    @Test
    void myGpaReturnsRealGpaAndRank() throws Exception {
        Map<String, Object> out = payloadAsMap("get_my_gpa", Map.of(), STUDENT);

        assertEquals(STUDENT, out.get("studentId"));
        assertEquals("ALL", out.get("term"), "省略 term 时必须是全部学期累计");
        assertEquals(0, new BigDecimal("3.8834").compareTo(new BigDecimal(String.valueOf(out.get("gpa")))),
                "平均学分绩点应为 3.8834，实际=" + out.get("gpa"));
        assertEquals(0, new BigDecimal("14.5").compareTo(new BigDecimal(String.valueOf(out.get("totalCredit")))),
                "计入学分应为 14.5，实际=" + out.get("totalCredit"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) out.get("details");
        Map<String, Object> cs101 = details.stream()
                .filter(d -> "CS101".equals(d.get("courseCode"))).findFirst().orElse(null);
        assertNotNull(cs101, "绩点明细里应有 CS101，实际=" + details);
        assertEquals(0, new BigDecimal("3.800000").compareTo(new BigDecimal(String.valueOf(cs101.get("gradePoint")))),
                "CS101 88 分对应绩点 3.8（88/10-5），实际=" + cs101.get("gradePoint"));

        @SuppressWarnings("unchecked")
        Map<String, Object> rank = (Map<String, Object>) out.get("rank");
        assertEquals(1, rank.get("rank"), "该生在同专业同年级应为第 1 名，实际=" + rank);
        assertNotNull(out.get("message"), "应带一句可直接转述的中文结论");
    }

    /** 培养方案：断言方案条目（而不是"非空"），并确认必修/选修分类被算出来 */
    @Test
    void myTrainingPlanReturnsRealPlanEntries() throws Exception {
        Map<String, Object> out = payloadAsMap("get_my_training_plan", Map.of(), STUDENT);

        assertEquals(STUDENT, out.get("studentId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) out.get("plan");
        assertNotNull(plan, "种子数据里该生应有适用方案，实际=" + out);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> courses = (List<Map<String, Object>>) out.get("courses");
        assertFalse(courses.isEmpty(), "方案里应有课程明细");
        assertTrue(courses.stream().anyMatch(c -> "必修".equals(c.get("categoryText"))),
                "应至少有一条必修课（categoryText 由服务端翻译）");
        assertTrue(courses.stream().allMatch(c -> c.get("courseCode") != null && c.get("credit") != null),
                "每条明细都应有课程代码与学分，实际=" + courses.get(0));
        assertEquals(courses.size(), out.get("courseCount"), "courseCount 必须与明细条数一致");
    }

    /** 学业预警：结构与阈值口径来自服务，断言字段齐全且阈值来自配置 */
    @Test
    void myAcademicWarningKeepsItsFields() throws Exception {
        Map<String, Object> out = payloadAsMap("get_my_academic_warning", Map.of(), STUDENT);

        assertTrue(out.containsKey("warned"));
        assertTrue(out.containsKey("failedCredits"));
        assertTrue(out.containsKey("failedCourseCount"));
        assertTrue(out.containsKey("threshold"));
        assertTrue(out.containsKey("courses"));
        assertNotNull(out.get("message"));
        assertTrue(new BigDecimal(String.valueOf(out.get("threshold"))).compareTo(BigDecimal.ZERO) > 0,
                "阈值应来自配置且大于 0");
    }

    /** 选课开放状态 / 课表 / 考试：三条都必须带上"查询范围"与"为什么是这个结果"的说明 */
    @Test
    void selectionStatusAndTimetableExplainThemselves() throws Exception {
        Map<String, Object> status = payloadAsMap("get_selection_status", Map.of(), STUDENT);
        assertEquals(STUDENT, status.get("studentId"));
        assertEquals("ALL", status.get("queriedTerm"), "省略 term 时应覆盖所有已配置轮次的学期");
        assertNotNull(status.get("message"), "必须有一句可直接转述的状态说明");

        Map<String, Object> timetable = payloadAsMap("list_my_class_times", Map.of(), STUDENT);
        assertEquals(STUDENT, timetable.get("studentId"));
        assertEquals("ALL", timetable.get("term"));
        assertTrue(timetable.get("courseCount") instanceof Number);
        assertNotNull(timetable.get("message"));

        Map<String, Object> exams = payloadAsMap("get_my_exams", Map.of(), STUDENT);
        assertEquals(STUDENT, exams.get("studentId"));
        assertEquals(Boolean.FALSE, exams.get("upcomingOnly"), "upcoming 省略时必须为 false（含已考完）");
        assertNotNull(exams.get("message"));
    }

    /** 毕业审核：派生值（satisfied/creditGap）必须被手工补上——record 派生方法不进 JSON */
    @Test
    void graduationAuditCarriesTheDerivedValues() throws Exception {
        Map<String, Object> out = payloadAsMap("audit_my_graduation", Map.of(), STUDENT);

        assertEquals(STUDENT, out.get("studentId"));
        assertTrue(out.containsKey("satisfied"), "satisfied 是派生值，必须手工补上");
        assertTrue(out.containsKey("creditGap"), "creditGap 是派生值，必须手工补上");
        assertTrue(out.containsKey("missingRequired"));
        assertTrue(out.containsKey("unmetElectiveCodes"));
        assertTrue(out.containsKey("earnedCredits"));
        // 派生值与分量必须自洽（只断言"键存在"会在值算错时依然通过）
        // 口径与生产代码一致：satisfied = planFound && creditSatisfied && requiredSatisfied && electiveSatisfied
        boolean expected = Boolean.TRUE.equals(out.get("planFound"))
                && Boolean.TRUE.equals(out.get("creditSatisfied"))
                && Boolean.TRUE.equals(out.get("requiredSatisfied"))
                && Boolean.TRUE.equals(out.get("electiveSatisfied"));
        assertEquals(expected, out.get("satisfied"), "satisfied 必须由 planFound 与三个分量推出");
    }

    // ==================================================================================
    // 三、写操作：走服务、有归属校验、失败可解释
    // ==================================================================================

    /**
     * 退课只影响本人：删掉一门课后，本人的已选课程里不再有它。
     *
     * <p>用真实库 + 回滚事务，所以不会污染种子数据。
     */
    @Test
    void dropCourseOnlyAffectsTheCaller() throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before = (List<Map<String, Object>>) objectMapper.readValue(
                String.valueOf(execute("get_my_courses", Map.of(), STUDENT)), List.class);
        assertFalse(before.isEmpty(), "种子数据里该生应有已选课程");
        Integer courseId = ((Number) before.get(0).get("courseId")).intValue();

        Object payload = execute("drop_course", Map.of("courseId", courseId), STUDENT);
        assertTrue(String.valueOf(payload).contains("退课成功"), "应返回退课成功，实际=" + payload);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = (List<Map<String, Object>>) objectMapper.readValue(
                String.valueOf(execute("get_my_courses", Map.of(), STUDENT)), List.class);
        assertTrue(after.stream().noneMatch(c -> ((Number) c.get("courseId")).intValue() == courseId),
                "退课后本人的已选课程里不应再有该课");
    }

    /** 评教的课程归属校验必须仍然生效：评价一门**没选过**的课会被服务端拒绝 */
    @Test
    void evaluateTeacherStillEnforcesOwnership() {
        // 课程 999999 不存在，服务端会给出可读原因（而不是写库）
        ToolDefinition def = tool("evaluate_teacher");
        ToolExecutionResult result = registry.executeForRole(def, ROLE,
                Map.of("courseId", 999999, "teacherId", "10001", "score", 5), STUDENT);

        // 手写版与声明式版都不 catch：服务异常由 ToolRegistry 兜成 FAILED（错误细节不外泄给模型）
        assertEquals(ToolExecutionResult.Status.FAILED, result.status(),
                "评价不存在的课程应失败，实际=" + result.status() + " 载荷=" + result.payload());
    }

    /** 评分越界：Schema 约束是主闸门（见 {@link #scoreConstraintIsEnforcedByTheSharedValidator}），
     *  方法体内的兜底校验则挡住"绕过参数校验器直接执行工具"的路径 */
    @Test
    void evaluateTeacherRejectsOutOfRangeScoreWithoutWriting() {
        ToolDefinition def = tool("evaluate_teacher");
        // 种子库里该生已评价过课程 1、4；课程 2 是"已选但未评价"的夹具，
        // 因此越界检查必须在任何写库动作之前生效——这正是本用例要证明的
        ToolExecutionResult result = registry.executeForRole(def, ROLE,
                Map.of("courseId", 2, "teacherId", "10002", "score", 6), STUDENT);

        assertTrue(result.isSuccess(), "越界应由工具自身返回可读原因（不是崩溃），实际 " + result.status());
        assertTrue(result.payload().contains("1~5"),
                "应明确告诉模型评分范围，实际=" + result.payload());
    }

    /** 冲突检查：三者都不传时给可读错误，而不是瞎猜一门课 */
    @Test
    void timeConflictCheckRefusesToGuessTheCourse() throws Exception {
        Map<String, Object> out = payloadAsMap("check_time_conflict", Map.of(), STUDENT);
        assertTrue(out.containsKey("error"), "不给任何课程线索时应返回可读错误，实际=" + out);
        assertFalse(out.containsKey("conflict"), "不应在没定位到课程时编造冲突结论");
    }

    /** 冲突检查：按课程名也能定位（用户实际只会说课名），并回显实际检查的课程与学期 */
    @Test
    void timeConflictCheckResolvesByCourseName() throws Exception {
        Map<String, Object> out = payloadAsMap("check_time_conflict", Map.of("courseName", "Java程序设计"), STUDENT);

        assertFalse(out.containsKey("error"), "按课名应能定位到课程，实际=" + out);
        assertEquals("CS101", out.get("courseCode"), "应定位到 CS101，实际=" + out);
        assertEquals("2024-2025-1", out.get("term"), "应回显实际检查的学期");
        assertTrue(out.containsKey("courseSlots"), "应返回该课程的上课时间");
        assertTrue(out.containsKey("conflict"), "应给出是否有冲突的结论");
        assertNotNull(out.get("message"));
    }

    /** 评价状态：已评价/未评价都必须给出布尔值与中文说明（两个方向都测，避免"恒 true"） */
    @Test
    void evaluationCheckReportsBothDirections() throws Exception {
        Map<String, Object> evaluated = payloadAsMap("check_evaluation", Map.of("courseId", 1), STUDENT);
        assertEquals(Boolean.TRUE, evaluated.get("evaluated"), "种子数据里课程 1 已评价，实际=" + evaluated);

        Map<String, Object> notEvaluated = payloadAsMap("check_evaluation", Map.of("courseId", 3), STUDENT);
        assertEquals(Boolean.FALSE, notEvaluated.get("evaluated"), "课程 3 未评价，实际=" + notEvaluated);
    }

    /** 我的评价：只返回本人提交的评价（种子数据里课程 1、4） */
    @Test
    void myEvaluationsReturnsOnlyOwnRows() throws Exception {
        List<Map<String, Object>> rows = objectMapper.readValue(
                String.valueOf(execute("get_my_evaluations", Map.of(), STUDENT)), new TypeReference<>() {
                });
        assertFalse(rows.isEmpty(), "种子数据里该生应有评价记录");
        assertTrue(rows.stream().allMatch(r -> STUDENT.equals(r.get("studentId"))),
                "只应返回本人评价，实际=" + rows);
    }

    /** 可选课程列表：不带关键词时返回全部可选课程（种子库有课），且支持按名称筛选 */
    @Test
    void courseListSupportsOptionalFilter() throws Exception {
        List<Map<String, Object>> all = objectMapper.readValue(
                String.valueOf(execute("get_course_list", Map.of(), STUDENT)), new TypeReference<>() {
                });
        assertFalse(all.isEmpty(), "可选课程列表不应为空（种子库有课程）");

        List<Map<String, Object>> filtered = objectMapper.readValue(
                String.valueOf(execute("get_course_list", Map.of("courseName", "Java"), STUDENT)),
                new TypeReference<>() {
                });
        assertFalse(filtered.isEmpty(), "按 Java 筛选应有结果，实际=" + filtered);
        assertTrue(filtered.size() <= all.size(), "筛选结果不应多于全部");
        assertTrue(filtered.stream().allMatch(c -> String.valueOf(c.get("courseName")).contains("Java")),
                "筛选结果都必须匹配关键词，实际=" + filtered);
    }

    /** 推荐课程：真机最常被问的一个，断言它给出可解释结论（推荐或说明为什么没有） */
    @Test
    void recommendCoursesAlwaysExplainsItself() throws Exception {
        Map<String, Object> out = payloadAsMap("recommend_courses", Map.of(), STUDENT);

        assertEquals(STUDENT, out.get("studentId"));
        assertTrue(out.containsKey("recommendations"), "必须给出推荐列表（可能为空）");
        assertTrue(out.containsKey("blocked"), "被规则挡住的也要返回，模型才能解释原因");
        assertNotNull(out.get("message"), "必须说明结论（有推荐 / 没缺口 / 轮次未开放）");
    }
}
