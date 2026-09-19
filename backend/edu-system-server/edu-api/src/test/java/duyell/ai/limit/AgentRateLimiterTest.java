package duyell.ai.limit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流器测试（真实 Redis）。
 *
 * <p>每个用例用独立 userId 并显式 {@link AgentRateLimiter#reset}，
 * 因为计数落在真实 Redis 上、带 1 分钟 TTL，共用 key 会让用例互相污染
 * （这正是审计表测试踩过的同一类坑）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentRateLimiterTest {

    @Autowired
    private AgentRateLimiter rateLimiter;

    private static String newUser() {
        return "rl-" + UUID.randomUUID();
    }

    @Test
    void allowsRequestsUpToTheLimit() {
        String user = newUser();
        rateLimiter.reset(user);
        try {
            for (int i = 1; i <= 3; i++) {
                assertTrue(rateLimiter.allowChat(user, 3),
                        "第 " + i + " 次请求在限额内应被允许");
            }
            assertEquals(3L, rateLimiter.currentChatCount(user));
        } finally {
            rateLimiter.reset(user);
        }
    }

    @Test
    void rejectsRequestsBeyondTheLimit() {
        String user = newUser();
        rateLimiter.reset(user);
        try {
            for (int i = 0; i < 2; i++) {
                assertTrue(rateLimiter.allowChat(user, 2));
            }
            assertFalse(rateLimiter.allowChat(user, 2), "超过限额的第 3 次应被拒绝");
            assertFalse(rateLimiter.allowChat(user, 2), "超限后继续请求仍应被拒绝");
        } finally {
            rateLimiter.reset(user);
        }
    }

    @Test
    void limitOfZeroMeansUnlimited() {
        String user = newUser();
        rateLimiter.reset(user);
        for (int i = 0; i < 50; i++) {
            assertTrue(rateLimiter.allowChat(user, 0), "限额为 0 表示不限制");
        }
        // 未限制时不应产生计数
        assertEquals(0L, rateLimiter.currentChatCount(user),
                "不限制时不该写 Redis 计数（省掉无谓的往返）");
    }

    @Test
    void counterIsIsolatedPerUser() {
        String a = newUser();
        String b = newUser();
        rateLimiter.reset(a);
        rateLimiter.reset(b);
        try {
            assertTrue(rateLimiter.allowChat(a, 1));
            assertFalse(rateLimiter.allowChat(a, 1), "a 已用满");

            assertTrue(rateLimiter.allowChat(b, 1), "b 的额度不应被 a 消耗");
            assertNotNull(rateLimiter.currentChatCount(b));
            assertEquals(1L, rateLimiter.currentChatCount(b));
        } finally {
            rateLimiter.reset(a);
            rateLimiter.reset(b);
        }
    }

    @Test
    void resetClearsTheWindow() {
        String user = newUser();
        rateLimiter.reset(user);
        try {
            assertTrue(rateLimiter.allowChat(user, 1));
            assertFalse(rateLimiter.allowChat(user, 1));

            rateLimiter.reset(user);

            assertTrue(rateLimiter.allowChat(user, 1), "重置后应重新获得额度");
        } finally {
            rateLimiter.reset(user);
        }
    }
}
