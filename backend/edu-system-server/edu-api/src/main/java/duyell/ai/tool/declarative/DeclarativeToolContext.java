package duyell.ai.tool.declarative;

import org.springframework.ai.chat.model.ToolContext;

/**
 * 声明式工具方法读取"当前调用者"的唯一入口。
 *
 * <p>为什么不让工具方法直接从参数里拿 userId：userId 来自 **token**，
 * 属于服务端事实；一旦它出现在参数 Schema 里，模型就能（有意或无意地）把它改成别人的学号，
 * 那就等于把越权读取的入口写进了工具契约。因此它只经 {@link ToolContext} 传入，
 * 由 {@code DeclarativeToolScanner} 填充，绝不进入模型可见的参数。
 *
 * <p>取不到就**直接失败**（fail closed）：默认成空串或 "unknown" 会让查询变成"查所有人"
 * 或"查不到"，两种都是错误答案，且很难从现象上分辨。
 *
 * @author duyell
 */
public final class DeclarativeToolContext {

    /** ToolContext 里存放当前用户 id 的键 */
    public static final String KEY_USER_ID = "userId";
    /** ToolContext 里存放当前角色的键 */
    public static final String KEY_ROLE = "role";

    private DeclarativeToolContext() {
    }

    /** 当前调用者（发起对话的用户） */
    public static String currentUserId(ToolContext context) {
        return required(context, KEY_USER_ID);
    }

    /** 当前调用者角色 */
    public static String currentRole(ToolContext context) {
        return required(context, KEY_ROLE);
    }

    private static String required(ToolContext context, String key) {
        Object value = context == null || context.getContext() == null
                ? null : context.getContext().get(key);
        String text = value == null ? null : String.valueOf(value);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(
                    "声明式工具缺少调用者上下文(" + key + ")：工具只能经 ToolRegistry.executeForRole 执行");
        }
        return text;
    }
}
