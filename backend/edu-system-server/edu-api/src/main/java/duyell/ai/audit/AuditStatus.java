package duyell.ai.audit;

/**
 * 审计状态取值。
 *
 * <p>用枚举而非散落的字符串字面量：状态会写进数据库并被查询/聚合，
 * 拼错一个字符串不会报错，只会让统计静默失真。
 */
public final class AuditStatus {

    private AuditStatus() {
    }

    /** 工具执行成功 */
    public static final String SUCCESS = "SUCCESS";

    /** 工具执行抛异常 */
    public static final String FAILED = "FAILED";

    /** 被拒绝：未知工具，或该工具不属于当前角色（越权） */
    public static final String DENIED = "DENIED";

    /** 危险操作经用户在前端明确取消或确认超时 */
    public static final String REJECTED_BY_USER = "REJECTED_BY_USER";

    /** 参数未通过 Schema 校验，工具未执行（由模型自行修正参数后重试） */
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

    /**
     * 幂等命中：同一 requestId 的操作此前已成功执行过，本次未重复执行。
     * 用于区分「真的执行了一次」与「请求重发但被幂等拦截」。
     */
    public static final String DUPLICATE_SKIPPED = "DUPLICATE_SKIPPED";
}
