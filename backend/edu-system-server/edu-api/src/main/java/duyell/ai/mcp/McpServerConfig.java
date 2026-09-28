package duyell.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.audit.AiAuditService;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **MCP server 装配**（M4）：把内部工具面以 Model Context Protocol 开放给外部客户端
 * （Claude Desktop / Cursor / Cline 等）。
 *
 * <p><b>为什么手工装配，而不是用 {@code spring-ai-starter-mcp-server-webmvc} 的自动配置</b>：
 * 那条路会把**容器里所有 {@code ToolCallback} bean 自动注册成 MCP 工具**
 * （自动配置的 {@code syncTools(...)} 就是这么写的）。本项目的工具面是**按角色隔离**的，
 * 自动注册等于把学生/教师/管理员的工具混成一份、并且绕过风险等级——那正是
 * {@code ToolRegistry} 类注释里记录过的越权翻车方式。这里显式地把"暴露哪些工具"交给
 * {@link McpToolExposure} 一个地方决定，装配只负责照单注册。
 *
 * <p><b>默认关闭</b>（{@code ai.mcp.enabled=false}），与 RAG 同样的取舍：
 * 一个"对外开口"的组件不该因为有人启动应用就悄悄开着。
 *
 * <p><b>鉴权与身份</b>：端点走与其它接口完全相同的 {@code LoginInterceptor}
 * （必须带 {@code token} 头，JWT + Redis 双校验）。工具调用时身份取自该请求上
 * 拦截器写入的 {@code username}/{@code role}（见 {@code contextExtractor}），
 * 再要求它与本服务配置的角色一致——所以**工具是以持令牌那个人的身份执行**，
 * 而不是某个共享账号；客户端也无法在参数里把自己变成别人。
 *
 * @author duyell
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "ai.mcp", name = "enabled", havingValue = "true")
public class McpServerConfig {

    /** MCP 会话 id：传输上下文里的键名由 SDK 决定，这里用 transport context 现取现用 */
    @Bean
    public WebMvcSseServerTransportProvider mcpTransportProvider(
            ObjectMapper objectMapper,
            @Value("${ai.mcp.sse-endpoint:/mcp/sse}") String sseEndpoint,
            @Value("${ai.mcp.message-endpoint:/mcp/message}") String messageEndpoint) {

        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .sseEndpoint(sseEndpoint)
                .messageEndpoint(messageEndpoint)
                // 把 HTTP 请求里"已鉴权的身份"带进 MCP 会话上下文：
                // 这两个属性是 LoginInterceptor 校验通过后写入的，因此身份与我们其它接口同源，
                // 不占用任何客户端可伪造的字段（MCP 的 arguments 里没有身份，也不该有）
                .contextExtractor(request -> {
                    Map<String, Object> ctx = new LinkedHashMap<>();
                    ctx.put(McpToolBridge.CTX_USERNAME, request.servletRequest().getAttribute("username"));
                    ctx.put(McpToolBridge.CTX_ROLE, request.servletRequest().getAttribute("role"));
                    // 会话 id 由传输层放在查询串上；带上它，审计里就能一眼看出"哪几次调用属于同一个外部会话"
                    request.param(McpToolBridge.CTX_SESSION_ID)
                            .ifPresent(id -> ctx.put(McpToolBridge.CTX_SESSION_ID, id));
                    return McpTransportContext.create(ctx);
                })
                .build();
    }

    /** 让 Spring MVC 把 MCP 的 SSE 与消息端点挂上去 */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider provider) {
        return provider.getRouterFunction();
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcpToolSpecifications(
            ToolRegistry toolRegistry,
            AiAuditService auditService,
            @Value("${ai.mcp.role:student}") String role,
            @Value("${ai.mcp.allow-writes:false}") boolean allowWrites) {

        McpToolExposure exposure = new McpToolExposure(toolRegistry, allowWrites);
        McpToolExposure.Exposure result = exposure.forRole(role);
        McpToolBridge bridge = new McpToolBridge(toolRegistry, auditService, role);

        // 启动日志必须把"开放了什么、为什么没开放别的"说清楚：
        // 否则使用者只看到工具少了一半，会先怀疑配置写错了
        log.info("MCP 工具面已装配: role={}, 开放 {} 个, 排除 {} 个, allowWrites={}",
                role, result.exposed().size(), result.excluded().size(), allowWrites);
        for (ToolDefinition def : result.exposed()) {
            log.info("  [MCP 开放] {} ({}) - {}", def.name(), def.riskLevel(), def.displayName());
        }
        for (McpToolExposure.Excluded excluded : result.excluded()) {
            log.info("  [MCP 排除] {} ({}) - {}", excluded.name(), excluded.riskLevel(), excluded.reason());
        }

        return result.exposed().stream().map(bridge::toSpecification).toList();
    }

    /**
     * MCP server 本体。
     *
     * <p>只声明 tools 能力：resources/prompts 本项目还没有对应实现，
     * 声明了却给不出内容，客户端会在调用时收到"方法未实现"——不如不声明。
     */
    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer mcpSyncServer(WebMvcSseServerTransportProvider provider,
                                       List<McpServerFeatures.SyncToolSpecification> tools,
                                       @Value("${ai.mcp.server-name:edu-system-mcp}") String serverName,
                                       @Value("${ai.mcp.server-version:1.0.0}") String version,
                                       @Value("${ai.mcp.sse-endpoint:/mcp/sse}") String sseEndpoint,
                                       @Value("${ai.mcp.role:student}") String role) {
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo(new McpSchema.Implementation(serverName, version))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .build();
        log.info("MCP server 已启动: name={}, version={}, role={}, tools={}, sse={}",
                serverName, version, role, tools.size(), sseEndpoint);
        return server;
    }
}
