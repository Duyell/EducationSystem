package duyell.ai.confirm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 人工确认闸门（Human-in-the-Loop）。
 *
 * <p>工作方式：Agent 执行线程在调用危险工具前调用 {@link #awaitDecision} 挂起并等待，
 * HTTP 侧由 {@code POST /ai/confirm} 调用 {@link #decide} 唤醒。
 * 二者通过 {@code confirmId} 关联，等待有超时上限，避免 Agent 线程永久阻塞。
 */
@Slf4j
@Component
public class ConfirmationGate {

    private final Map<String, Action> pending = new ConcurrentHashMap<>();

    /**
     * 挂起等待用户决定。
     *
     * @return 用户是否同意执行
     */
    public DecisionResult awaitDecision(String confirmId, Duration timeout) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(confirmId, new Action(future));
        try {
            Boolean approved = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return approved != null && approved ? DecisionResult.APPROVED : DecisionResult.REJECTED;
        } catch (TimeoutException e) {
            return DecisionResult.TIMEOUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DecisionResult.INTERRUPTED;
        } catch (Exception e) {
            log.error("确认闸门异常 confirmId={}", confirmId, e);
            return DecisionResult.TIMEOUT;
        } finally {
            pending.remove(confirmId);
        }
    }

    /**
     * 记录用户的选择并唤醒等待中的 Agent 线程。
     *
     * @return true 表示本次请求真正产生了决定；false 表示该确认已失效或已被处理（幂等）
     */
    public boolean decide(String confirmId, boolean approved) {
        if (confirmId == null) return false;
        Action action = pending.get(confirmId);
        if (action == null) {
            log.debug("确认请求已失效或已被处理 confirmId={}", confirmId);
            return false;
        }
        return action.future().complete(approved);
    }

    /** 取消等待（如 SSE 连接断开），让 Agent 线程立即释放 */
    public void cancel(String confirmId, String reason) {
        Action action = pending.get(confirmId);
        if (action != null) {
            log.debug("取消待确认操作 confirmId={}, reason={}", confirmId, reason);
            action.future().complete(false);
        }
    }

    /** 是否存在等待中的确认（仅供测试与诊断） */
    public boolean isWaiting(String confirmId) {
        return pending.containsKey(confirmId);
    }

    private record Action(CompletableFuture<Boolean> future) {
    }

    /** 确认结果 */
    public enum DecisionResult {
        /** 用户确认 */
        APPROVED,
        /** 用户取消 */
        REJECTED,
        /** 超时未响应 */
        TIMEOUT,
        /** 线程被中断 */
        INTERRUPTED
    }
}
