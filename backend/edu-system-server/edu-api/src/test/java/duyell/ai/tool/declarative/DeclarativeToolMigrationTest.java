package duyell.ai.tool.declarative;

import com.duyell.Course;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 声明式工具迁移测试（M2 计划 1.3）。
 *
 * <p>迁移的**唯一风险**不是"框架能不能跑"，而是**换成声明式之后，那四道闸门还在不在**：
 * 角色白名单、参数 Schema 校验、危险操作人工确认、审计留痕。
 * 因此本测试刻意不去测"注解解析得对不对"，而是逐条盯着这些安全契约：
 * <ol>
 *   <li><b>危险等级与确认</b>：{@code select_course} 迁移后仍必须是 DANGEROUS
 *       （{@code requiresConfirmation()} 为 true，HITL 卡片由此触发）；</li>
 *   <li><b>参数必填</b>：模型不能再"不带 courseId 调用选课"——框架生成的 Schema 里
 *       {@code required} 必须还在，否则校验器会放行一个必然失败（或更糟：选错课）的调用；</li>
 *   <li><b>调用者身份不进 Schema</b>：userId 只能来自 token（ToolContext），
 *       一旦它出现在参数里，模型就能改成别人的学号；</li>
 *   <li><b>角色隔离</b>：学生与教师同名工具的"按角色隔离"在迁移后依然成立。</li>
 * </ol>
 *
 * <p>再加一条**数据正确性**断言（返回真实课程，而不是空列表）：
 * 这正是 2026-09-22 那个"学生问'我选了什么课'、助手答'你没选任何课'"的 bug 的形态，
 * 只有断言到业务数据才能抓到。
 *
 * <p>用真实库 + 回滚事务隔离，可反复跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DeclarativeToolMigrationTest {

    private static final String STUDENT = "2023001";
    private static final String TEACHER = "10001";

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private CourseSelectionMapper courseSelectionMapper;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private ToolDefinition tool(String role, String name) {
        ToolDefinition def = registry.getTool(role, name);
        assertNotNull(def, role + " 角色下应有工具 " + name);
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

    /** ① 危险写工具迁移后仍然是"必须人工确认" */
    @Test
    void migratedDangerousToolStillRequiresConfirmation() {
        ToolDefinition select = tool("student", "select_course");
        assertEquals(RiskLevel.DANGEROUS, select.riskLevel(),
                "选课必须保持 DANGEROUS，否则确认卡片不再弹出");
        assertTrue(select.requiresConfirmation(), "requiresConfirmation() 是 HITL 的开关");

        ToolDefinition courses = tool("student", "get_my_courses");
        assertEquals(RiskLevel.READ_ONLY, courses.riskLevel(), "只读查询不该要求确认");
        assertFalse(courses.requiresConfirmation());
    }

    /** ② 参数必填与描述都还在（框架生成的 Schema 不能被迁移"简化"掉约束） */
    @Test
    void migratedSchemaKeepsRequiredArguments() {
        ToolDefinition select = tool("student", "select_course");
        assertTrue(properties(select).containsKey("courseId"),
                "参数里必须有 courseId，实际=" + properties(select));
        assertEquals(List.of("courseId"), required(select),
                "courseId 必须仍是必填——否则模型可以不带课程ID就触发选课");

        Object courseIdSchema = properties(select).get("courseId");
        assertTrue(String.valueOf(courseIdSchema).contains("integer"),
                "courseId 应是整型，实际=" + courseIdSchema);

        ToolDefinition courses = tool("student", "get_my_courses");
        assertEquals(List.of(), required(courses), "无参数工具不该有必填项");
    }

    /**
     * ③ 调用者身份**不在**参数 Schema 里。
     *
     * <p>ToolContext 只是方法参数，不是工具参数：它若漏进 Schema，模型就会看到
     * 一个可填的 userId 字段，从而能"替别人查/选课"。
     */
    @Test
    void callerIdentityNeverAppearsInTheSchema() {
        for (String name : List.of("get_my_courses", "select_course")) {
            ToolDefinition def = tool("student", name);
            String schema = String.valueOf(def.parameters());
            assertFalse(schema.contains(DeclarativeToolContext.KEY_USER_ID),
                    name + " 的参数 Schema 不得出现 userId：" + schema);
            assertFalse(schema.toLowerCase().contains("toolcontext"),
                    name + " 的参数 Schema 不得出现 ToolContext：" + schema);
        }
    }

    /** ④ 迁移后角色隔离依然成立（学生与教师同名工具是两份实现） */
    @Test
    void roleIsolationSurvivesMigration() {
        assertEquals("我的已选课程", tool("student", "get_my_courses").displayName());
        assertEquals("我的授课课程", tool("teacher", "get_my_courses").displayName(),
                "教师那份必须还是教师实现（学生实现只在 student 角色下接管）");
        assertTrue(registry.getToolsByRole("student").stream()
                .anyMatch(d -> "select_course".equals(d.name())));
    }

    /** 数据正确性：声明式版本必须返回该生真实的已选课程（不是空列表） */
    @Test
    void declarativeToolReturnsRealSelections() throws Exception {
        ToolExecutionResult result = registry.executeForRole(
                tool("student", "get_my_courses"), "student", Map.of(), STUDENT);
        assertTrue(result.isSuccess(), "执行应成功，实际 " + result.status() + " / " + result.errorDetail());

        List<Map<String, Object>> rows = objectMapper.readValue(result.payload(),
                new TypeReference<>() {
                });
        List<Integer> ids = rows.stream().map(r -> ((Number) r.get("courseId")).intValue()).toList();
        assertTrue(ids.containsAll(List.of(1, 2, 3, 4, 5, 6)),
                "应返回该生已选课程 1~6，实际=" + ids);

        // 空列表是"查错人/查错表"的典型症状，这里再单独钉一次
        assertFalse(rows.isEmpty(), "返回空列表通常意味着查的不是本人数据");
    }

    /**
     * 声明式写工具真的写库了（走的是同一个工具执行入口）。
     *
     * <p>选一门该生**尚未选**的课，执行后断言选课记录出现；事务回滚保证可重复跑。
     */
    @Test
    void declarativeWriteToolActuallyWritesThroughTheSameGate() {
        Integer targetCourseId = null;
        for (Course course : courseMapper.list(null, null, null, null, null, null, null)) {
            if (courseSelectionMapper.select(course.getId(), STUDENT) == null) {
                targetCourseId = course.getId();
                break;
            }
        }
        assertNotNull(targetCourseId, "找不到可用来测试的未选课程");

        ToolExecutionResult result = registry.executeForRole(
                tool("student", "select_course"), "student", Map.of("courseId", targetCourseId), STUDENT);
        assertTrue(result.isSuccess(), "工具应执行成功，实际 " + result.status());
        assertTrue(result.payload().contains("成功"),
                "未选过的课应当选成功，实际=" + result.payload());
        assertNotNull(courseSelectionMapper.select(targetCourseId, STUDENT),
                "选课必须真的落库（不是只回了一句'成功'）");
    }

    /**
     * 身份缺失时**失败**而不是"当成查所有人"。
     *
     * <p>直接调用工具执行器（绕过 ToolRegistry 的上下文注入）来模拟"忘了带调用者"：
     * 必须抛错，不能返回全员数据、也不能静默返回空。
     */
    @Test
    void missingCallerFailsClosedInsteadOfQueryingEveryone() {
        ToolDefinition def = tool("student", "get_my_courses");
        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> def.executor().execute(Map.of(), null, "student"));
        assertTrue(String.valueOf(thrown.getMessage()).contains("调用者上下文")
                        || String.valueOf(thrown.getCause()).contains("调用者上下文"),
                "应明确报'缺少调用者上下文'，实际=" + thrown);
    }
}
