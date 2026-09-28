package duyell.ai.mcp;

import duyell.ai.audit.AiAuditService;
import duyell.ai.audit.AuditStatus;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 把本项目的 {@link ToolDefinition} **桥接**成 MCP 工具。
 *
 * <p><b>桥接的要点是"不新增第二条执行路径"</b>：外部调用与模型调用最终都走
 * {@link ToolRegistry#executeForRole}，因此角色白名单、参数校验后的执行语义、
 * 变更上下文（{@code ChangeContext.SOURCE_AI}，让成绩变更日志能区分"界面改的"与"AI 改的"）
 * 全部自动继承。反过来说，如果这里图省事直接调 {@code def.executor().execute(...)}，
 * 就会绕开白名单——那是本项目 2026-09-22 修过的越权 bug 的翻版。
 *
 * <p><b>JSON Schema 逐字透传</b>：MCP 的 {@code inputSchema} 由我们的 {@code parameters}
 * 原样转换而来（{@code properties} 直接引用同一份 Map），因此
 * "成绩只能在 0~100"这类 {@code minimum}/{@code maximum} 约束在 MCP 客户端侧同样可见。
 * 这是刻意保证的：工具契约换一个协议不该变形，否则模型/客户端会在参数上乱猜。
 *
 * <p><b>身份**不来自客户端自称**</b>：调用者身份取自 HTTP 请求经过 {@code LoginInterceptor}
 * 后写入的 {@code username}/{@code role} 属性（见 {@link McpCaller}），
 * 再要求它等于本 MCP server 配置的角色。客户端无法通过参数把自己变成别人。
 *
 * @author duyell
 */
@Slf4j
public class McpToolBridge {

    /** 传输上下文里携带调用者信息的键（由 {@code McpServerConfig} 的 contextExtractor 写入） */
    public static final String CTX_USERNAME = "username";
    public static final String CTX_ROLE = "role";
    public static final String CTX_SESSION_ID = "sessionId";

    private final ToolRegistry toolRegistry;
    private final AiAuditService auditService;
    private final String expectedRole;

    public McpToolBridge(ToolRegistry toolRegistry, AiAuditService auditService, String expectedRole) {
        this.toolRegistry = toolRegistry;
        this.auditService = auditService;
        this.expectedRole = expectedRole;
    }

    /** 工具定义 → MCP 工具（名称/描述/Schema 全部透传，展示名放进 title 供客户端显示） */
    public McpSchema.Tool toMcpTool(ToolDefinition def) {
        return McpSchema.Tool.builder()
                .name(def.name())
                .title(def.displayName())
                .description(def.description())
                .inputSchema(toJsonSchema(def.parameters()))
                .build();
    }

    /** 工具定义 → MCP 工具规格（含调用处理器） */
    public McpServerFeatures.SyncToolSpecification toSpecification(ToolDefinition def) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(toMcpTool(def))
                .callHandler((exchange, request) -> call(def, exchange, request))
                .build();
    }

    /**
     * 执行一次 MCP 工具调用。
     *
     * <p>三条失败路径都要**回成 MCP 结果而不是异常**：客户端期望的是一个 `isError=true` 的结果，
     * 好把原因转述给使用者；抛异常只会变成一次协议层错误，客户端拿不到"为什么被拒"。
     */
    private McpSchema.CallToolResult call(ToolDefinition def, McpSyncServerExchange exchange,
                                          McpSchema.CallToolRequest request) {
        McpCaller caller = McpCaller.from(exchange.transportContext());
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

        if (caller == null) {
            return error("未认证：MCP 工具调用需要携带 token（与其它接口一致）");
        }
        if (!expectedRole.equals(caller.role())) {
            // 工具面按配置的角色注册，调用者角色不符就直接拒绝：否则一个学生 token 能调用教师工具面。
            // 状态必须用 AuditStatus 的取值（这里是 DENIED）：手写一个"看起来更贴切"的字符串
            // （例如 ROLE_FORBIDDEN）会游离在枚举之外，让按状态聚合的统计静默失真 ——
            // 具体原因写进 errorMsg/结果文本即可。
            log.warn("MCP 调用被拒：角色不匹配。调用者={}({}), 本服务工具面角色={}",
                    caller.username(), caller.role(), expectedRole);
            audit(caller, def, args, null, AuditStatus.DENIED,
                    "MCP 角色不匹配：调用者角色 " + caller.role() + " 与本服务工具面角色 " + expectedRole + " 不符", 0L);
            return error("无权限：本 MCP 服务开放的是「" + expectedRole + "」角色的工具面");
        }

        long started = System.currentTimeMillis();
        ToolExecutionResult result = toolRegistry.executeForRole(def, caller.role(), args, caller.username());
        long duration = System.currentTimeMillis() - started;

        audit(caller, def, args, result.payload(),
                result.status().name(), result.errorDetail(), duration);

        if (result.isSuccess()) {
            log.info("MCP 工具调用: tool={}, user={}, session={}, status=SUCCESS, durationMs={}",
                    def.name(), caller.username(), caller.sessionId(), duration);
        } else {
            log.warn("MCP 工具调用失败/被拒: tool={}, user={}, status={}, detail={}",
                    def.name(), caller.username(), result.status(), result.errorDetail());
        }
        // payload 已由工具层脱敏（异常细节只进日志/审计），可以原样交给外部客户端
        return text(result.payload(), !result.isSuccess());
    }

    /**
     * 一次 MCP 调用落一条审计。
     *
     * <p>复用 {@code session_id} 列记 MCP 会话：运行时路径下它一直是 null，
     * 因此"有 session_id 的行"天然就是外部 MCP 调用——**不加列**就把这两类调用分开了，
     * 而审计表的结构与既有查询口径都不用动。
     */
    private void audit(McpCaller caller, ToolDefinition def, Map<String, Object> args,
                       String payload, String status, String errorDetail, long durationMs) {
        auditService.record(caller.username(), caller.role(), def.name(),
                def.riskLevel() == null ? "READ_ONLY" : def.riskLevel().name(),
                args, payload, status, errorDetail, null, durationMs,
                null, caller.auditSessionId());
    }

    private McpSchema.CallToolResult text(String text, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text == null ? "" : text)))
                .isError(isError)
                .build();
    }

    private McpSchema.CallToolResult error(String message) {
        return text(message, true);
    }

    /**
     * 本项目的参数 Schema → MCP 的 {@code JsonSchema}。
     *
     * <p>{@code properties} 是**同一份 Map 引用**（不深拷贝）：约束（minimum/maximum/enum…）
     * 全都藏在每个属性自己的子 Schema 里，重写一遍就等于给自己一个"漏掉某个约束"的机会。
     */
    @SuppressWarnings("unchecked")
    static McpSchema.JsonSchema toJsonSchema(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        }
        Object type = parameters.get("type");
        Object properties = parameters.get("properties");
        Object required = parameters.get("required");
        Object additional = parameters.get("additionalProperties");
        return new McpSchema.JsonSchema(
                type instanceof String s ? s : "object",
                properties instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(),
                required instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(),
                additional instanceof Boolean b ? b : Boolean.FALSE,
                null, null);
    }

    /**
     * 一次 MCP 调用的调用者身份。
     *
     * <p>由 {@code McpServerConfig} 的 contextExtractor 从 HTTP 请求里取出
     * （值来自 {@code LoginInterceptor} 校验通过后写入的请求属性），
     * 因此"身份"与其它接口同源，不占用任何客户端可伪造的字段。
     */
    public record McpCaller(String username, String role, String sessionId) {

        static McpCaller from(McpTransportContext context) {
            if (context == null) {
                return null;
            }
            Object username = context.get(CTX_USERNAME);
            Object role = context.get(CTX_ROLE);
            if (!(username instanceof String u) || u.isBlank() || !(role instanceof String r) || r.isBlank()) {
                return null;
            }
            Object session = context.get(CTX_SESSION_ID);
            return new McpCaller(u, r, session instanceof String s ? s : null);
        }

        /** 供测试构造 */
        public static McpCaller of(String username, String role) {
            return new McpCaller(username, role, "test-session");
        }

        /** 审计表里的 session_id 取值：加前缀，便于一眼看出是外部 MCP 调用 */
        public String auditSessionId() {
            return sessionId == null ? "mcp" : "mcp:" + sessionId;
        }
    }
}
