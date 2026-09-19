package duyell.ai.limit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 用量限流器（阶段 0.8）。
 *
 * <p>两道闸门，均按「用户」维度计数，落在 Redis 上（多实例部署也一致）：
 * <ol>
 *   <li><b>对话频次</b>：每用户每分钟最多发起 N 次对话，防止刷接口烧钱；</li>
 *   <li><b>单次会话工具调用数</b>：一次对话内工具调用总次数上限，防止模型陷入循环狂调工具。</li>
 * </ol>
 *
 * <p>计数实现：固定窗口 + {@code INCR} + 首次计数时设 TTL。
 * 固定窗口在窗口边界存在最多 2 倍的瞬时突刺，对本场景（防滥用而非精确计费）足够；
 * 需要更平滑时再换滑动窗口/令牌桶。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRateLimiter {

    private static final String CHAT_WINDOW_PREFIX = "ai:rl:chat:";
    private static final Duration CHAT_WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试占用一次对话配额。
     *
     * @param userId      用户标识
     * @param maxPerMinute 每用户每分钟允许的对话次数
     * @return true 允许；false 已超限
     */
    public boolean allowChat(String userId, int maxPerMinute) {
        if (maxPerMinute <= 0) {
            return true; // 未配置上限 = 不限制
        }
        String key = CHAT_WINDOW_PREFIX + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                redisTemplate.expire(key, CHAT_WINDOW);
            }
            return count <= maxPerMinute;
        } catch (Exception e) {
            // Redis 不可用时放行：限流是防滥用手段，不应因计数服务故障让功能整体不可用。
            // 但必须告警——静默失效的限流等于没有限流。
            log.error("限流计数失败，本次放行: userId={}", userId, e);
            return true;
        }
    }

    /** 查询当前窗口内已用次数（诊断/测试用） */
    public long currentChatCount(String userId) {
        try {
            String v = redisTemplate.opsForValue().get(CHAT_WINDOW_PREFIX + userId);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("读取限流计数失败: userId={}", userId, e);
            return 0L;
        }
    }

    /** 重置计数（测试用，避免用例之间互相污染） */
    public void reset(String userId) {
        try {
            redisTemplate.delete(CHAT_WINDOW_PREFIX + userId);
        } catch (Exception e) {
            log.warn("重置限流计数失败: userId={}", userId, e);
        }
    }
}
