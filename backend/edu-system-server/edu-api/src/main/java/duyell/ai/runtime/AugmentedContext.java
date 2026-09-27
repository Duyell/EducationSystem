package duyell.ai.runtime;

import java.util.List;
import java.util.Map;

/**
 * 上下文增强的**产物**：既要给模型的文本，也要给界面看的出处。
 *
 * <p><b>为什么不是单纯的 {@code String}</b>（M3 收尾补的）：强制注入的条款是在服务端检索出来的，
 * 用户其实**应该**看到"这段回答依据了哪几条制度"——但第一版只返回文本，于是注入路径没有来源卡片，
 * 只有"模型自己调 search_policy"那条路径才有。同一次检索，一个有一份没有，用户会以为注入的回答没依据
 * （恰恰相反：注入的才是被服务端保证过的）。
 *
 * <p>{@code sources} 沿用与工具结果**完全相同的形状**（{@code {docId, docTitle, section, citation}}），
 * 因为下游只有一条渲染路径（{@link AgentEventType#SOURCES}）；两种来源在前端应当长得一模一样。
 *
 * @param text    追加到系统提示词后的文本；不需要注入时为 {@code null}
 * @param sources 本次注入条款的出处，每项形如 {@code {docId, docTitle, section, citation}}；
 *                无出处时为空列表（**永不为 null**，调用方不必判空）
 * @author duyell
 */
public record AugmentedContext(String text, List<Map<String, Object>> sources) {

    /** 不做任何增强（也是失败降级后的取值） */
    public static final AugmentedContext NONE = new AugmentedContext(null, List.of());

    public AugmentedContext {
        // 归一化到"空列表"而不是 null：运行时要拿它直接判空并转发，不该到处写 null 检查
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 只有文本、没有出处（例如"检索不到条款"这种提示语） */
    public static AugmentedContext textOnly(String text) {
        return new AugmentedContext(text, List.of());
    }

    /** 有没有值得注入的文本 */
    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
