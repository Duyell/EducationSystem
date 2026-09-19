package duyell.ai.confirm;

import duyell.ai.tool.RiskLevel;

import java.util.Map;

/**
 * 待用户确认的危险操作。
 *
 * <p>安全设计：工具的<b>名称与参数只在服务端持有</b>。前端仅拿到一个不可猜测的
 * {@code confirmId}，恢复执行时也只回传该 ID —— 前端无法借此调用任意工具。
 */
public record PendingAction(
        String confirmId,
        String userId,
        String role,
        String toolName,
        String displayName,
        Map<String, Object> arguments,
        RiskLevel riskLevel,
        long createdAt
) {
}
