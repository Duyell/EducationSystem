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

    /**
     * 全部合法取值。
     *
     * <p><b>为什么需要这个集合</b>：这些状态是 {@code String} 常量而不是枚举，
     * 写错一个字符串编译器不会拦；而状态列会被查询与聚合，
     * 拼错只会让统计**静默失真**——例如 MCP 桥接里手写过一个 {@code "ROLE_FORBIDDEN"}，
     * 它既不在本集合里、也没人发现，直到 {@code .dsh/verify-p5-audit.ps1}
     * 的"每个状态都是已知取值"这条断言把它揪出来。
     * 现在 {@code AiAuditService} 写入前会校验一次，未知取值直接告警。
     */
    private static final java.util.Set<String> KNOWN = java.util.Set.of(
            SUCCESS, FAILED, DENIED, REJECTED_BY_USER, INVALID_ARGUMENTS, DUPLICATE_SKIPPED);

    /** 该状态是否为已知取值（供写入前自检与测试使用） */
    public static boolean isKnown(String status) {
        return status != null && KNOWN.contains(status);
    }

    /** 已知取值的只读集合（测试与排查用） */
    public static java.util.Set<String> known() {
        return KNOWN;
    }
}
