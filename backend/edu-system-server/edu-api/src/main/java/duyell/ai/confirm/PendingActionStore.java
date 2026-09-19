package duyell.ai.confirm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 待确认操作的暂存区（Redis，带 TTL）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li>工具名与参数只存在服务端，前端只有 confirmId；</li>
 *   <li>取值时必须校验归属用户，防止用别人的 confirmId 代为确认；</li>
 *   <li>TTL 到期自动清理，用户长时间不响应不会留下悬挂状态。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingActionStore {

    private static final String KEY_PREFIX = "ai:pending:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(PendingAction action, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(action.confirmId()),
                    objectMapper.writeValueAsString(action), ttl);
        } catch (Exception e) {
            log.warn("暂存待确认操作失败 confirmId={}", action.confirmId(), e);
        }
    }

    /** 取出并校验归属；不存在/已过期/不属于该用户时返回 null */
    public PendingAction getForUser(String confirmId, String userId) {
        if (confirmId == null || confirmId.isBlank() || userId == null) return null;
        try {
            String json = redisTemplate.opsForValue().get(key(confirmId));
            if (json == null) return null;
            PendingAction action = objectMapper.readValue(json, PendingAction.class);
            if (!userId.equals(action.userId())) {
                log.warn("confirmId 归属校验失败: confirmId={}, owner={}, requester={}",
                        confirmId, action.userId(), userId);
                return null;
            }
            return action;
        } catch (Exception e) {
            log.warn("读取待确认操作失败 confirmId={}", confirmId, e);
            return null;
        }
    }

    public void remove(String confirmId) {
        try {
            redisTemplate.delete(key(confirmId));
        } catch (Exception e) {
            log.warn("清理待确认操作失败 confirmId={}", confirmId, e);
        }
    }

    private String key(String confirmId) {
        return KEY_PREFIX + confirmId;
    }
}
