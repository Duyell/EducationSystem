package duyell.ai.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具白名单与风险分级的不变量测试。
 *
 * <p>这两条是安全底线，任何重构都不允许破坏：
 * <ol>
 *   <li>模型给出的工具名不可信 —— 越权调用必须被拒绝且<b>不执行</b>；</li>
 *   <li>危险工具必须带确认标记（requiresConfirmation），否则 HITL 形同虚设。</li>
 * </ol>
 */
class ToolRegistryTest {

    private ToolDefinition tool(String name, RiskLevel risk, AtomicBoolean executed) {
        return new ToolDefinition(name, name, name, Map.of("type", "object", "properties", Map.of()),
                risk, (args, userId, role) -> {
            executed.set(true);
            return "{\"ok\":true}";
        });
    }

    @Test
    void executeForRoleExecutesAllowedTool() {
        ToolRegistry registry = new ToolRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);
        registry.register("student", tool("select_course", RiskLevel.DANGEROUS, executed));

        ToolExecutionResult result = registry.executeForRole(
                registry.getTool("select_course"), "student", Map.of("courseId", 5), "2023001");

        assertTrue(executed.get(), "本角色工具应当被执行");
        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
    }

    @Test
    void executeForRoleDeniesCrossRoleTool() {
        ToolRegistry registry = new ToolRegistry();
        AtomicBoolean executed = new AtomicBoolean(false);
        registry.register("teacher", tool("enter_score", RiskLevel.DANGEROUS, executed));

        // 学生试图调用教师工具（工具存在，但不属于 student 角色）
        ToolExecutionResult result = registry.executeForRole(
                registry.getTool("enter_score"), "student", Map.of("courseId", 5), "2023001");

        assertFalse(executed.get(), "越权工具绝不能被执行");
        assertEquals(ToolExecutionResult.Status.DENIED, result.status());
        assertFalse(registry.isAllowedForRole("student", "enter_score"));
        assertTrue(registry.isAllowedForRole("teacher", "enter_score"));
    }

    @Test
    void executeForRoleDeniesUnknownTool() {
        ToolRegistry registry = new ToolRegistry();

        ToolExecutionResult result = registry.executeForRole(
                null, "student", Map.of(), "2023001");

        assertEquals(ToolExecutionResult.Status.DENIED, result.status());
        assertEquals("UNKNOWN_TOOL", result.payload());
    }

    @Test
    void failedToolDoesNotLeakExceptionDetailToModel() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("student", new ToolDefinition(
                "boom", "boom", "boom",
                Map.of("type", "object", "properties", Map.of()),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    throw new IllegalStateException("Table 'edujwxt.sys_user' doesn't exist");
                }));

        ToolExecutionResult result = registry.executeForRole(
                registry.getTool("boom"), "student", Map.of(), "2023001");

        assertEquals(ToolExecutionResult.Status.FAILED, result.status());
        assertFalse(result.payload().contains("sys_user"), "回灌模型的内容不得包含内部细节");
        assertFalse(result.payload().contains("IllegalStateException"));
        assertNotNull(result.errorDetail(), "原始异常信息应保留给日志/审计");
    }

    @Test
    void dangerLevelDrivesConfirmation() {
        ToolDefinition dangerous = new ToolDefinition("select_course", "选课", "选课", Map.of(),
                RiskLevel.DANGEROUS, (a, u, r) -> "ok");
        ToolDefinition write = new ToolDefinition("drop_course", "退课", "退课", Map.of(),
                RiskLevel.WRITE, (a, u, r) -> "ok");
        ToolDefinition readOnly = new ToolDefinition("get_my_courses", "我的课程", "查询", Map.of(),
                RiskLevel.READ_ONLY, (a, u, r) -> "ok");

        assertTrue(dangerous.requiresConfirmation(), "选课必须人工确认");
        assertFalse(write.requiresConfirmation(), "可逆写操作直接执行");
        assertFalse(readOnly.requiresConfirmation(), "只读查询直接执行");
    }

    @Test
    void roleToolListingIsIsolated() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("student", tool("get_my_courses", RiskLevel.READ_ONLY, new AtomicBoolean()));
        registry.register("teacher", tool("enter_score", RiskLevel.DANGEROUS, new AtomicBoolean()));

        List<String> studentTools = registry.getToolsByRole("student").stream()
                .map(ToolDefinition::name).toList();
        List<String> teacherTools = registry.getToolsByRole("teacher").stream()
                .map(ToolDefinition::name).toList();

        assertEquals(List.of("get_my_courses"), studentTools);
        assertEquals(List.of("enter_score"), teacherTools);
        assertTrue(registry.getToolsByRole("unknown-role").isEmpty());
    }

    @Test
    void writeSemanticsAreDetectedForSelfCheck() {
        assertTrue(new ToolDefinition("enter_score", "d", "d", Map.of(),
                RiskLevel.READ_ONLY, (a, u, r) -> "ok").looksLikeWriteOperation());
        assertTrue(new ToolDefinition("update_score", "d", "d", Map.of(),
                RiskLevel.READ_ONLY, (a, u, r) -> "ok").looksLikeWriteOperation());
        assertFalse(new ToolDefinition("get_my_scores", "d", "d", Map.of(),
                RiskLevel.READ_ONLY, (a, u, r) -> "ok").looksLikeWriteOperation());
    }
}
