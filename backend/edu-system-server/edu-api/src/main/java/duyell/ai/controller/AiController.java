package duyell.ai.controller;

import duyell.ai.config.AiProperties;
import duyell.ai.service.AiChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import utils.Result;

import java.util.Map;

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
