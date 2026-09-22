package duyell.audit;

/**
 * "这次改动是谁做的、从哪来的"上下文（线程级）。
 *
 * <p><b>为什么需要它</b>：业务 Service（如 {@code ScoreServiceImpl}）是成绩写入的必经之路，
 * 但它拿不到"当前请求是谁发的"——controller 有 request、AI 工具执行器有 userId，Service 都没有。
 * 与其把 operator 参数一路往下传（污染所有方法签名、且漏传一次就丢一条留痕），
 * 不如在**两个边界**设置上下文：
 * <ul>
 *   <li>REST 边界：{@code ScoreController} 从 token 取用户名/角色后设置；</li>
 *   <li>AI 边界：{@code ToolRegistry.executeForRole} 用调用者的 userId/role 设置，来源标记为 AI。</li>
 * </ul>
 *
 * <p><b>使用约定</b>：一律用 {@link #runWith}（try/finally 里清理），不要手动 set 后忘记 clear——
 * 线程池复用会把上一个人的身份带给下一个人。
 *
 * <p>取值默认是 {@code unknown}/{@link #SOURCE_UI}：即使边界忘了设置，也只会记成"未知操作人"，
 * 不会丢记录、也不会错记成别人。
 *
 * @author duyell
 */
public final class ChangeContext {

    /** 来源：界面或普通接口 */
    public static final String SOURCE_UI = "UI";
    /** 来源：智能助手（工具调用） */
    public static final String SOURCE_AI = "AI";

    private static final ChangeContext DEFAULT =
            new ChangeContext("unknown", null, SOURCE_UI);

    private static final ThreadLocal<ChangeContext> CURRENT = new ThreadLocal<>();

    private final String operatorId;
    private final String operatorRole;
    private final String source;

    private ChangeContext(String operatorId, String operatorRole, String source) {
        this.operatorId = operatorId;
        this.operatorRole = operatorRole;
        this.source = source;
    }

    /** 当前上下文（永不为 null） */
    public static ChangeContext current() {
        ChangeContext ctx = CURRENT.get();
        return ctx == null ? DEFAULT : ctx;
    }

    /** 在指定身份下执行一段逻辑，结束后恢复原上下文 */
    public static void runWith(String operatorId, String operatorRole, String source, Runnable action) {
        ChangeContext previous = CURRENT.get();
        CURRENT.set(new ChangeContext(
                operatorId == null || operatorId.isBlank() ? "unknown" : operatorId,
                operatorRole,
                source == null ? SOURCE_UI : source));
        try {
            action.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public String operatorId() {
        return operatorId;
    }

    public String operatorRole() {
        return operatorRole;
    }

    public String source() {
        return source;
    }
}
