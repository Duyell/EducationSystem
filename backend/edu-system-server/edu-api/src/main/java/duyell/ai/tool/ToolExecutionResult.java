package duyell.ai.tool;

/**
 * 工具执行结果。
 *
 * <p>注意 {@code errorDetail} 与 {@code payload} 的分工：
 * <ul>
 *   <li>{@code payload} —— 回灌给模型/前端的可读信息（脱敏）；</li>
 *   <li>{@code errorDetail} —— 仅供服务端日志与审计表使用的原始异常信息。</li>
 * </ul>
 */
public record ToolExecutionResult(
        Status status,
        String payload,
        String errorDetail,
        long durationMs
) {

    public enum Status {
        /** 执行成功 */
        SUCCESS,
        /** 执行失败（业务或系统异常） */
        FAILED,
        /** 被拒绝：未知工具或角色越权 */
        DENIED
    }

    public static ToolExecutionResult ok(String payload, long durationMs) {
        return new ToolExecutionResult(Status.SUCCESS, payload, null, durationMs);
    }

    public static ToolExecutionResult denied(String payload, String detail, long durationMs) {
        return new ToolExecutionResult(Status.DENIED, payload, detail, durationMs);
    }

    public static ToolExecutionResult failed(Exception e, long durationMs) {
        return new ToolExecutionResult(Status.FAILED, "操作失败，请稍后重试", e.toString(), durationMs);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
