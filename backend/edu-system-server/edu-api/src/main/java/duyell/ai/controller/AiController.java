package duyell.ai.controller;

import duyell.ai.config.AiProperties;
import duyell.ai.service.AiChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import utils.Result;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService aiChatService;
    private final AiProperties aiProperties;

    /**
     * AI 对话接口（SSE 流式响应）
     */
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody Map<String, String> body,
                           HttpServletRequest request) {
        String message = body.get("message");
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

        return aiChatService.chat(message, token);
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
}
