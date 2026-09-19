package duyell.ai.confirm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 人工确认闸门（HITL）行为测试。
 *
 * <p>关注点：挂起/唤醒能正确配对、超时不会永久阻塞 Agent 线程、重复确认具备幂等性。
 */
class ConfirmationGateTest {

    private static final Duration SHORT = Duration.ofMillis(600);
    private static final Duration ENOUGH = Duration.ofSeconds(3);

    @Test
    void awaitingThenApprovingReturnsApproved() throws Exception {
        ConfirmationGate gate = new ConfirmationGate();
        CompletableFuture<ConfirmationGate.DecisionResult> decision = new CompletableFuture<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> trigger = scheduler.schedule(
                () -> gate.decide("c1", true), 100, TimeUnit.MILLISECONDS);

        try {
            decision.complete(gate.awaitDecision("c1", ENOUGH));
            assertEquals(ConfirmationGate.DecisionResult.APPROVED, decision.get());
            assertFalse(gate.isWaiting("c1"), "确认完成后必须清理等待状态");
        } finally {
            trigger.cancel(true);
            scheduler.shutdownNow();
        }
    }

    @Test
    void awaitingThenRejectingReturnsRejected() throws Exception {
        ConfirmationGate gate = new ConfirmationGate();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> trigger = scheduler.schedule(
                () -> gate.decide("c2", false), 100, TimeUnit.MILLISECONDS);

        try {
            assertEquals(ConfirmationGate.DecisionResult.REJECTED,
                    gate.awaitDecision("c2", ENOUGH));
            assertFalse(gate.isWaiting("c2"));
        } finally {
            trigger.cancel(true);
            scheduler.shutdownNow();
        }
    }

    @Test
    void awaitRespectsTimeoutAndReleasesTheThread() {
        ConfirmationGate gate = new ConfirmationGate();

        long start = System.currentTimeMillis();
        ConfirmationGate.DecisionResult result = gate.awaitDecision("c3", SHORT);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(ConfirmationGate.DecisionResult.TIMEOUT, result);
        assertTrue(elapsed >= 500, "应确实等待了超时时长，实际 " + elapsed + "ms");
        assertTrue(elapsed < 3000, "不得无限阻塞，实际 " + elapsed + "ms");
        assertFalse(gate.isWaiting("c3"), "超时后必须清理等待状态");
    }

    @Test
    void decidingAnUnknownOrAlreadyDecidedIdIsRejected() {
        ConfirmationGate gate = new ConfirmationGate();

        assertFalse(gate.decide("never-existed", true), "无等待者时不得报告成功");
        assertFalse(gate.decide(null, true), "空 confirmId 必须安全返回 false");

        // 超时后再次确认同样应被拒绝（防止前端延迟点击导致的重复执行）
        assertEquals(ConfirmationGate.DecisionResult.TIMEOUT, gate.awaitDecision("c4", SHORT));
        assertFalse(gate.decide("c4", true));
    }

    @Test
    void secondDecisionOnSameIdIsIdempotent() throws Exception {
        ConfirmationGate gate = new ConfirmationGate();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> first = scheduler.schedule(
                () -> gate.decide("c5", true), 100, TimeUnit.MILLISECONDS);

        try {
            assertEquals(ConfirmationGate.DecisionResult.APPROVED, gate.awaitDecision("c5", ENOUGH));
            // 同一个 confirmId 的第二次点击不再生效
            assertFalse(gate.decide("c5", true));
        } finally {
            first.cancel(true);
            scheduler.shutdownNow();
        }
    }
}
