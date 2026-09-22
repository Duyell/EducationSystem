package duyell.ai.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 把事件推给浏览器（SSE）。
 *
 * <p>行为与改造前 {@code AiChatService#sendSse} **逐字一致**：
 * 发送失败（客户端已断开）只记日志、不抛异常——一次断开不该把整轮对话变成 500。
 * `complete()` 同样吞掉异常：连接早已关闭时 `complete()` 也会抛。
 *
 * @author duyell
 */
@Slf4j
@RequiredArgsConstructor
public class SseAgentEventPublisher implements AgentEventPublisher {

    private final SseEmitter emitter;

    @Override
    public void publish(AgentEvent event) {
        try {
            emitter.send(SseEmitter.event().name("message").data(event.toPayload()));
        } catch (IOException | IllegalStateException e) {
            // IllegalStateException：响应已提交/已关闭（例如用户在流式过程中关掉页面）
            log.warn("SSE 事件发送失败: type={}", event.type(), e);
        }
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 失败（连接多半已关闭）", e);
        }
    }
}
