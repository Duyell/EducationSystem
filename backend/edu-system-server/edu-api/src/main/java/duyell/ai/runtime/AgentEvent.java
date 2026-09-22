package duyell.ai.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 Agent 事件（不可变的载荷）。
 *
 * <p>字段全部可空、按需出现：线协议里"没有这个键"与"这个键是 null"对前端是两回事
 * （前者不覆盖状态，后者可能把状态清掉），因此 {@link #toPayload()} **只放非 null 字段**，
 * 与改造前的 {@code Map.of(...)} 行为逐字一致。
 *
 * @param type            事件类型
 * @param content         文本内容（正文 / 状态文案 / 错误信息）
 * @param tool            工具名（模型使用的英文名，脚本靠它做机器判断）
 * @param displayName     工具中文展示名（给人看）
 * @param riskLevel       风险等级（READ_ONLY / WRITE / DANGEROUS）
 * @param conversationId  会话 id
 * @param confirmId       确认令牌（HITL）
 * @param args            待确认的工具参数（给确认卡片展示）
 * @param timeoutSeconds  确认超时（秒）
 * @param approved        用户是否批准
 * @param expired         确认是否已超时失效
 *
 * @author duyell
 */
public record AgentEvent(
        AgentEventType type,
        String content,
        String tool,
        String displayName,
        String riskLevel,
        String conversationId,
        String confirmId,
        Map<String, Object> args,
        Integer timeoutSeconds,
        Boolean approved,
        Boolean expired
) {

    public static AgentEvent token(String content) {
        return new AgentEvent(AgentEventType.TOKEN, content, null, null, null, null, null, null, null, null, null);
    }

    public static AgentEvent status(String content) {
        return new AgentEvent(AgentEventType.STATUS, content, null, null, null, null, null, null, null, null, null);
    }

    /** 带工具信息的状态：前端/脚本据此知道"模型到底选了哪个工具" */
    public static AgentEvent status(String content, String tool, String displayName, String riskLevel) {
        return new AgentEvent(AgentEventType.STATUS, content, tool, displayName, riskLevel,
                null, null, null, null, null, null);
    }

    public static AgentEvent conversation(String conversationId) {
        return new AgentEvent(AgentEventType.CONVERSATION, null, null, null, null, conversationId,
                null, null, null, null, null);
    }

    public static AgentEvent confirm(String confirmId, String tool, String displayName,
                                     Map<String, Object> args, int timeoutSeconds) {
        return new AgentEvent(AgentEventType.CONFIRM, null, tool, displayName, null, null,
                confirmId, args, timeoutSeconds, null, null);
    }

    public static AgentEvent confirmResult(String confirmId, boolean approved, boolean expired) {
        return new AgentEvent(AgentEventType.CONFIRM_RESULT, null, null, null, null, null,
                confirmId, null, null, approved, expired);
    }

    public static AgentEvent error(String content) {
        return new AgentEvent(AgentEventType.ERROR, content, null, null, null, null, null, null, null, null, null);
    }

    /** 错误 + 原始工具名：越权尝试要被脚本精确识别，只给一段中文文案是没法机器判断的 */
    public static AgentEvent error(String content, String tool) {
        return new AgentEvent(AgentEventType.ERROR, content, tool, null, null, null, null, null, null, null, null);
    }

    public static AgentEvent done() {
        return new AgentEvent(AgentEventType.DONE, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 线协议载荷：**只保留非 null 字段**（顺序固定，便于人和脚本阅读）。
     *
     * <p>{@code type} 用 {@link AgentEventType#wireName()}：这是与旧实现
     * （`Map.of("type", "token", ...)`）逐字兼容的关键，改动它等于改前端契约。
     */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type.wireName());
        if (content != null) {
            payload.put("content", content);
        }
        if (tool != null) {
            payload.put("tool", tool);
        }
        if (displayName != null) {
            payload.put("displayName", displayName);
        }
        if (riskLevel != null) {
            payload.put("riskLevel", riskLevel);
        }
        if (conversationId != null) {
            payload.put("conversationId", conversationId);
        }
        if (confirmId != null) {
            payload.put("confirmId", confirmId);
        }
        if (args != null) {
            payload.put("args", args);
        }
        if (timeoutSeconds != null) {
            payload.put("timeoutSeconds", timeoutSeconds);
        }
        if (approved != null) {
            payload.put("approved", approved);
        }
        if (expired != null) {
            payload.put("expired", expired);
        }
        return payload;
    }
}
