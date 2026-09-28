package duyell.ai.mcp;

import com.duyell.AiToolAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.audit.AiAuditService;
import duyell.ai.audit.AuditStatus;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import duyell.mapper.AiToolAuditMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 桥接层的单元测试：**调用与审计**这条链（不启 Spring、不连库）。
 *
 * <p>为什么值得单独测：这里有一个真实发生过的缺陷。
 * 角色不匹配时桥接层手写了一个审计状态 {@code "ROLE_FORBIDDEN"} —— 它不在
 * {@link AuditStatus} 的取值集合里，编译与运行都不报错，只是让"按状态聚合"的统计静默失真；
 * 最后是被 {@code .dsh/verify-p5-audit.ps1} 的"每个状态都是已知取值"那条断言揪出来的。
 * 因此这里把"**写进审计的状态必须来自 AuditStatus**"钉成断言，
 * 让下次有人再手写一个"看起来更贴切"的字符串时立刻变红。
 *
 * <p>用假的 mapper 捕获写入实体，而不是 Mockito（本项目测试类路径上没有 Mockito）。
 */
class McpToolBridgeTest {

    /** 捕获写入内容的审计 mapper 桩：只实现本测试用到的方法，其余抛错（用到即说明测试写错了） */
    private static final class CapturingAuditMapper implements AiToolAuditMapper {
        final List<AiToolAudit> inserted = new ArrayList<>();

        @Override
        public void insert(AiToolAudit audit) {
            inserted.add(audit);
        }

        @Override
        public List<AiToolAudit> listByUser(String userId, int limit) {
            throw new UnsupportedOperationException("本测试不查询");
        }

        @Override
        public AiToolAudit findSuccessfulByRequestId(String requestId) {
            throw new UnsupportedOperationException("本测试不走幂等路径");
        }

        @Override
        public int countByStatus(String status) {
            throw new UnsupportedOperationException("本测试不统计");
        }
    }

    private static ToolDefinition tool(String name, List<String> executed) {
        return new ToolDefinition(name, name + "展示名", "描述",
                Map.of("type", "object", "properties", Map.of()), RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    executed.add(userId + "/" + role);
                    return "{\"ok\":true}";
                });
    }

    private static McpToolBridge bridgeWith(ToolRegistry registry, CapturingAuditMapper mapper) {
        return new McpToolBridge(registry, new AiAuditService(mapper, new ObjectMapper()), "student");
    }

    /** 造一个带身份（或空身份）的 MCP 传输上下文 */
    private static McpTransportContext contextOf(String username, String role, String sessionId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (username != null) {
            ctx.put(McpToolBridge.CTX_USERNAME, username);
            ctx.put(McpToolBridge.CTX_ROLE, role);
        }
        if (sessionId != null) {
            ctx.put(McpToolBridge.CTX_SESSION_ID, sessionId);
        }
        return McpTransportContext.create(ctx);
    }

    /**
     * 造一个带给定上下文的 exchange。
     *
     * <p>SDK 的 {@code McpSyncServerExchange} 由它自己构造、transport context 无法注入，
     * 因此用最小匿名子类覆盖 {@code transportContext()}——本测试只走这一条路径，
     * 其它成员（createMessage 等）一律用不到。
     */
    private static McpSyncServerExchange exchangeWith(McpTransportContext ctx) {
        return new McpSyncServerExchange(null) {
            @Override
            public McpTransportContext transportContext() {
                return ctx;
            }
        };
    }

    /** 跑一次调用，返回结果 */
    private static McpSchema.CallToolResult call(McpToolBridge bridge, ToolRegistry registry,
                                                 String toolName, McpTransportContext ctx) {
        McpServerFeatures.SyncToolSpecification spec =
                bridge.toSpecification(registry.getTool("student", toolName));
        return spec.callHandler().apply(exchangeWith(ctx),
                new McpSchema.CallToolRequest(toolName, Map.of()));
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce("", (a, b) -> a + b);
    }

    // ------------------------------------------------------------------
    // 身份解析：上下文中没有身份时不得当成"某个默认用户"
    // ------------------------------------------------------------------
    @Test
    void callerWithoutIdentityIsNull() {
        assertNull(McpToolBridge.McpCaller.from(contextOf(null, null, null)),
                "没有 username/role 的上下文必须解析为 null（未认证），而不是造一个默认身份");
        assertNull(McpToolBridge.McpCaller.from(null));
    }

    @Test
    void callerIdentityComesFromContextAndKeepsSessionId() {
        McpToolBridge.McpCaller caller =
                McpToolBridge.McpCaller.from(contextOf("2023001", "student", "sess-1"));

        assertNotNull(caller);
        assertEquals("2023001", caller.username());
        assertEquals("student", caller.role());
        assertEquals("mcp:sess-1", caller.auditSessionId(), "审计里要能看出是哪一次外部会话");
    }

    /** 没有会话号时也要给一个可辨识的值，而不是空串 */
    @Test
    void auditSessionIdFallsBackToPlainMcpPrefix() {
        assertEquals("mcp", new McpToolBridge.McpCaller("2023001", "student", null).auditSessionId());
    }

    // ------------------------------------------------------------------
    // 审计状态取值：手写字符串造成过真实缺陷，这里钉住
    // ------------------------------------------------------------------
    @Test
    void handWrittenStatusWouldBeCaughtByTheKnownSet() {
        assertTrue(AuditStatus.isKnown(AuditStatus.DENIED));
        assertTrue(AuditStatus.isKnown(AuditStatus.SUCCESS));
        assertFalse(AuditStatus.isKnown("ROLE_FORBIDDEN"),
                "ROLE_FORBIDDEN 曾被手写进审计，导致按状态聚合的统计失真；它不该是已知取值");
        assertFalse(AuditStatus.isKnown(null));
    }

    // ------------------------------------------------------------------
    // 工具契约透传
    // ------------------------------------------------------------------
    @Test
    void mcpToolKeepsNameDescriptionTitleAndSchema() {
        ToolDefinition def = new ToolDefinition("get_my_gpa", "我的绩点与排名", "查询本人绩点",
                Map.of("type", "object",
                        "properties", Map.of("term", Map.of("type", "string")),
                        "required", List.of("term")),
                RiskLevel.READ_ONLY, (args, userId, role) -> "{}");

        McpSchema.Tool mcpTool = bridgeWith(new ToolRegistry(), new CapturingAuditMapper()).toMcpTool(def);

        assertEquals("get_my_gpa", mcpTool.name());
        assertEquals("我的绩点与排名", mcpTool.title(), "展示名要让外部客户端看得懂");
        assertEquals("查询本人绩点", mcpTool.description());
        assertEquals("object", mcpTool.inputSchema().type());
        assertEquals(List.of("term"), mcpTool.inputSchema().required());
        assertTrue(mcpTool.inputSchema().properties().containsKey("term"));
    }

    /** 每个开放的工具都要能变成带调用处理器的 MCP 规格（漏一个，客户端就少一个工具） */
    @Test
    void everyExposedToolBecomesASpecificationWithACallHandler() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("student", tool("get_my_courses", new ArrayList<>()));
        registry.register("student", tool("get_my_scores", new ArrayList<>()));
        McpToolBridge bridge = bridgeWith(registry, new CapturingAuditMapper());

        List<McpServerFeatures.SyncToolSpecification> specs = new McpToolExposure(registry, false)
                .forRole("student").exposed().stream().map(bridge::toSpecification).toList();

        assertEquals(2, specs.size());
        for (McpServerFeatures.SyncToolSpecification spec : specs) {
            assertNotNull(spec.callHandler(), "规格必须带调用处理器：" + spec.tool().name());
            assertNotNull(spec.tool().name());
        }
    }

    // ------------------------------------------------------------------
    // 三条调用路径
    // ------------------------------------------------------------------

    /** 正常调用：身份取自上下文（token），工具真的执行，审计写 SUCCESS + 会话号 */
    @Test
    void successfulCallExecutesAsTheTokenHolderAndIsAudited() {
        ToolRegistry registry = new ToolRegistry();
        List<String> executed = new ArrayList<>();
        registry.register("student", tool("get_my_courses", executed));
        CapturingAuditMapper mapper = new CapturingAuditMapper();
        McpToolBridge bridge = bridgeWith(registry, mapper);

        McpSchema.CallToolResult result = call(bridge, registry, "get_my_courses",
                contextOf("2023001", "student", "abc-123"));

        assertEquals(List.of("2023001/student"), executed,
                "身份必须来自 MCP 上下文（token），参数里没有也不该有身份");
        assertFalse(Boolean.TRUE.equals(result.isError()), "正常调用不应是错误结果");
        assertEquals("{\"ok\":true}", textOf(result));

        assertEquals(1, mapper.inserted.size());
        AiToolAudit row = mapper.inserted.get(0);
        assertEquals(AuditStatus.SUCCESS, row.getStatus());
        assertEquals("2023001", row.getUserId());
        assertEquals("student", row.getRole());
        assertEquals("get_my_courses", row.getToolName());
        assertEquals("READ_ONLY", row.getRiskLevel());
        assertEquals("mcp:abc-123", row.getSessionId(),
                "外部调用靠 session_id 与内置 Agent 区分（运行时路径这一列一直是 null）");
    }

    /** 角色不符：拒绝 + 审计状态必须是**已知取值** + 工具绝不执行 */
    @Test
    void roleMismatchIsRejectedAuditedWithKnownStatusAndNeverExecuted() {
        ToolRegistry registry = new ToolRegistry();
        List<String> executed = new ArrayList<>();
        registry.register("student", tool("get_my_courses", executed));
        CapturingAuditMapper mapper = new CapturingAuditMapper();
        McpToolBridge bridge = bridgeWith(registry, mapper);

        McpSchema.CallToolResult result = call(bridge, registry, "get_my_courses",
                contextOf("10001", "teacher", "sess-9"));

        assertTrue(Boolean.TRUE.equals(result.isError()), "角色不符必须是错误结果");
        assertTrue(executed.isEmpty(), "角色不符时工具绝不能被执行");
        assertTrue(textOf(result).contains("student"), "拒绝文案要说清本服务开放的是哪个角色面");

        assertEquals(1, mapper.inserted.size(), "拒绝也要留审计");
        AiToolAudit row = mapper.inserted.get(0);
        assertEquals(AuditStatus.DENIED, row.getStatus(),
                "状态必须来自 AuditStatus（曾经手写过 ROLE_FORBIDDEN，导致统计失真）");
        assertTrue(row.getErrorMsg() != null && row.getErrorMsg().contains("角色"),
                "拒绝原因要能看懂：" + row.getErrorMsg());
    }

    /** 未认证（上下文里没有身份）：拒绝、不执行，且不写审计（连用户都没有，写了就是脏行） */
    @Test
    void missingIdentityIsRejectedWithoutExecutingOrAuditing() {
        ToolRegistry registry = new ToolRegistry();
        List<String> executed = new ArrayList<>();
        registry.register("student", tool("get_my_courses", executed));
        CapturingAuditMapper mapper = new CapturingAuditMapper();

        McpSchema.CallToolResult result = call(bridgeWith(registry, mapper), registry, "get_my_courses",
                contextOf(null, null, null));

        assertTrue(Boolean.TRUE.equals(result.isError()), "未认证必须是错误结果");
        assertTrue(executed.isEmpty(), "未认证时工具绝不能被执行");
        assertTrue(mapper.inserted.isEmpty(), "未认证时不写审计（避免 user_id 为空的脏行）");
        assertTrue(textOf(result).contains("未认证"), "文案要说清原因：" + textOf(result));
    }
}
