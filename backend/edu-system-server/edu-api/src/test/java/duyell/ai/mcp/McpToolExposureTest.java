package duyell.ai.mcp;

import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具暴露策略的单元测试。
 *
 * <p>它盯的是本项目里**唯一一处"把内部能力交给外部进程"**的边界，所以断言不看"能不能跑通"，
 * 只看"放行/拒绝的名单对不对"：
 * <ol>
 *   <li>只读工具放行；</li>
 *   <li>写工具默认不放行，显式开启后才放行；</li>
 *   <li><b>危险工具无论怎么配都不放行</b>——它的安全性来自人工确认，而 MCP 没有这条通道；</li>
 *   <li>放行 + 排除 = 该角色的全部工具（不能有"谁都没提到"的工具悄悄消失）；</li>
 *   <li>角色隔离仍然成立（学生工具面里不该出现教师工具）。</li>
 * </ol>
 */
class McpToolExposureTest {

    private static ToolDefinition tool(String name, RiskLevel risk) {
        return new ToolDefinition(name, name + "展示名", "描述",
                Map.of("type", "object", "properties", Map.of()), risk,
                (args, userId, role) -> "{}");
    }

    private static ToolRegistry registryWithStudentTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("student", tool("get_my_courses", RiskLevel.READ_ONLY));
        registry.register("student", tool("get_my_gpa", RiskLevel.READ_ONLY));
        registry.register("student", tool("drop_course", RiskLevel.WRITE));
        registry.register("student", tool("select_course", RiskLevel.DANGEROUS));
        registry.register("student", tool("evaluate_teacher", RiskLevel.DANGEROUS));
        return registry;
    }

    @Test
    void readOnlyToolsAreExposedByDefault() {
        McpToolExposure.Exposure exposure = new McpToolExposure(registryWithStudentTools(), false)
                .forRole("student");

        assertEquals(List.of("get_my_courses", "get_my_gpa"), exposure.exposedNames());
    }

    @Test
    void writeToolsNeedAnExplicitOptIn() {
        ToolRegistry registry = registryWithStudentTools();

        assertFalse(new McpToolExposure(registry, false).forRole("student")
                .exposedNames().contains("drop_course"), "默认不该开放写工具");

        assertTrue(new McpToolExposure(registry, true).forRole("student")
                .exposedNames().contains("drop_course"), "显式开启后写工具应可开放");
    }

    /**
     * **核心安全断言**：危险工具永不外放，即使显式把开关全打开。
     *
     * <p>理由写在 {@link McpToolExposure} 的类注释里：这类工具的安全性来自"服务端挂起 + 用户点确认"，
     * MCP 没有确认通道。若哪天有人为了"好用"放开它，这条测试会红——这正是它存在的意义。
     */
    @Test
    void dangerousToolsAreNeverExposedEvenWithWritesAllowed() {
        McpToolExposure.Exposure exposure = new McpToolExposure(registryWithStudentTools(), true)
                .forRole("student");

        assertFalse(exposure.exposedNames().contains("select_course"), "危险工具不得外放：" + exposure.exposedNames());
        assertFalse(exposure.exposedNames().contains("evaluate_teacher"), "危险工具不得外放：" + exposure.exposedNames());
        // 且必须给出**原因**（启动日志会打出来，避免使用者以为配置写错了）
        assertTrue(exposure.excluded().stream()
                        .filter(e -> "select_course".equals(e.name()))
                        .anyMatch(e -> e.reason().contains("人工确认")),
                "排除原因应说明是 HITL 通道缺失：" + exposure.excluded());
    }

    /** 放行 + 排除必须等于该角色的全部工具：不能有工具"谁都没提到"地消失 */
    @Test
    void everyToolIsEitherExposedOrExplicitlyExcluded() {
        McpToolExposure.Exposure exposure = new McpToolExposure(registryWithStudentTools(), false)
                .forRole("student");

        List<String> accounted = new java.util.ArrayList<>(exposure.exposedNames());
        exposure.excluded().forEach(e -> accounted.add(e.name()));

        assertEquals(5, accounted.size(), "5 个工具都应被点名：" + accounted);
        assertTrue(accounted.containsAll(
                List.of("get_my_courses", "get_my_gpa", "drop_course", "select_course", "evaluate_teacher")));
    }

    /** 角色隔离：学生的工具面里不该出现教师侧工具 */
    @Test
    void exposureIsScopedToTheRequestedRole() {
        ToolRegistry registry = registryWithStudentTools();
        registry.register("teacher", tool("get_course_students", RiskLevel.READ_ONLY));

        McpToolExposure.Exposure student = new McpToolExposure(registry, true).forRole("student");
        McpToolExposure.Exposure teacher = new McpToolExposure(registry, true).forRole("teacher");

        assertFalse(student.exposedNames().contains("get_course_students"));
        assertEquals(List.of("get_course_students"), teacher.exposedNames());
    }

    /**
     * 工具契约换协议不该变形：{@code minimum}/{@code maximum} 这类约束藏在属性自己的子 Schema 里，
     * 必须原样出现在 MCP 的 inputSchema 中（否则外部客户端的模型会在参数上乱猜）。
     */
    @Test
    void jsonSchemaKeepsNestedConstraints() {
        ToolDefinition scoreTool = new ToolDefinition("enter_score", "录入成绩", "描述",
                Map.of("type", "object",
                        "properties", Map.of("usualScore", Map.of("type", "integer",
                                "minimum", 0, "maximum", 100)),
                        "required", List.of("usualScore")),
                RiskLevel.READ_ONLY, (args, userId, role) -> "{}");

        var schema = McpToolBridge.toJsonSchema(scoreTool.parameters());

        assertEquals("object", schema.type());
        assertEquals(List.of("usualScore"), schema.required());
        @SuppressWarnings("unchecked")
        Map<String, Object> usual = (Map<String, Object>) schema.properties().get("usualScore");
        assertEquals(0, usual.get("minimum"));
        assertEquals(100, usual.get("maximum"));
    }

    /** 没有参数的工具也要给一个合法的空 object schema（MCP 客户端会校验它） */
    @Test
    void emptyParametersBecomeAnEmptyObjectSchema() {
        var schema = McpToolBridge.toJsonSchema(null);

        assertEquals("object", schema.type());
        assertTrue(schema.properties().isEmpty());
        assertTrue(schema.required().isEmpty());
    }
}
