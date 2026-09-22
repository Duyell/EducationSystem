package duyell.ai.controller;

import duyell.ai.config.AiProperties;
import duyell.ai.service.AiChatService;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import utils.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService aiChatService;
    private final AiProperties aiProperties;
    private final ToolRegistry toolRegistry;

    /**
     * AI 对话接口（SSE 流式响应）。
     *
     * <p>请求体：{@code {message, conversationId?}}。{@code conversationId} 省略时服务端
     * 新建一个会话，并通过 SSE 的 {@code conversation} 事件把 id 告诉前端
     * （见 {@code AiChatService#chat}）。
     */
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody Map<String, String> body,
                           HttpServletRequest request) {
        String message = body.get("message");
        String conversationId = body.get("conversationId");
        String token = request.getHeader("token");

        if (message == null || message.isBlank()) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(Map.of("type", "error", "content", "请输入消息内容")));
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }

        return aiChatService.chat(message, token, conversationId);
    }

    /**
     * 危险操作的人工确认（HITL）恢复接口。
     *
     * <p>前端只回传 confirmId 与决定；工具名与参数始终由服务端暂存记录提供，
     * 因此前端无法借此调用任意工具。确认请求必须来自该操作的发起用户。
     */
    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        String confirmId = body.get("confirmId") == null ? null : body.get("confirmId").toString();
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        String userId = (String) request.getAttribute("username");

        if (confirmId == null || confirmId.isBlank()) {
            return Result.error("400", "缺少 confirmId");
        }
        boolean accepted = aiChatService.resolveConfirmation(confirmId, userId, approved);
        if (!accepted) {
            log.warn("确认请求未被受理: confirmId={}, userId={}", confirmId, userId);
            return Result.error("410", "该操作已失效或已被处理，请重新发起");
        }
        return Result.success(Map.of("confirmId", confirmId, "approved", approved));
    }

    /**
     * 检查 AI 配置状态
     */
    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig() {
        String apiKey = aiProperties.getApiKey();
        boolean configured = apiKey != null && !apiKey.isBlank();
        Map<String, Object> config = Map.of(
                "configured", configured,
                "model", aiProperties.getModel(),
                "baseUrl", aiProperties.getBaseUrl()
        );
        return Result.success(config);
    }

    /**
     * 当前角色可用的 Agent 工具清单（含描述、参数 Schema、风险等级）。
     *
     * <p>用途有两个：
     * <ol>
     *   <li>评测脚本据此断言"模型看到的到底是什么"，而不是在脚本里另抄一份工具定义——
     *       抄一份迟早会与注册器里的真实描述漂移，评测就失去意义；</li>
     *   <li>排查"模型为什么不选某个工具"时，可以直接看到它收到的描述。</li>
     * </ol>
     *
     * <p>只返回**当前登录角色自己的**工具（角色取自 token，不接受入参），
     * 因此不存在越权读取他人工具集的问题。**不含 system prompt**：
     * 提示词留在服务端，评测要走真实对话链路（{@code /ai/chat}）而不是本地拼一遍。
     */
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools(HttpServletRequest request) {
        String role = (String) request.getAttribute("role");
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ToolDefinition def : toolRegistry.getToolsByRole(role)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", def.name());
            item.put("displayName", def.displayName());
            item.put("description", def.description());
            item.put("parameters", def.parameters() == null ? Map.of() : def.parameters());
            item.put("riskLevel", def.riskLevel() == null ? null : def.riskLevel().name());
            item.put("requiresConfirmation", def.requiresConfirmation());
            payload.add(item);
        }
        return Result.success(payload);
    }
}
