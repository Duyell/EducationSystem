package duyell.ai.runtime;

import java.util.List;
import java.util.Map;

/**
 * **记住本轮来源**的事件出口装饰器（M3 来源卡片持久化）。
 *
 * <p><b>它解决什么</b>：来源（出处）原本只作为一条 {@link AgentEventType#SOURCES} 事件推给前端，
 * 落库时拿不到——因为落库发生在运行时跑完之后，而那时事件已经发完了，出处只剩在流里。
 * 于是刷新历史会话卡片就消失。这里把流经的事件**顺手抄一份**，
 * 调用方跑完循环后即可把出处与正文一起落库。
 *
 * <p><b>为什么用装饰器而不是让运行时返回值多带一个出处字段</b>：
 * 运行时（{@code AgentRuntime}）不该知道"持久化"这件事——它只负责编排与发事件。
 * 需要落库的是接入层（会话/落库都属于接入层职责），而接入层恰好是**创建这个出口的人**，
 * 所以"在出口上抄一份"是最贴合职责边界的做法，运行时的接口一行都不用改。
 *
 * <p><b>只保留最后一条</b>：一轮对话可能发多次来源（模型先自己检索一次、之后服务端又注入一次），
 * 而落库的是**这一轮最终那条助手消息**，它的依据就是最后那批来源。
 * 简单地去重合并会让卡片列出"检索过但没用上"的条款，反而失真。
 *
 * @author duyell
 */
public class SourceCapturingPublisher implements AgentEventPublisher {

    private final AgentEventPublisher delegate;

    /** 最近一次来源（volatile：事件在运行时的线程发出，落库在接入层线程读取） */
    private volatile List<Map<String, Object>> lastSources = List.of();

    public SourceCapturingPublisher(AgentEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(AgentEvent event) {
        if (event != null && event.type() == AgentEventType.SOURCES) {
            Object node = event.args() == null ? null : event.args().get("sources");
            if (node instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> sources = (List<Map<String, Object>>) list;
                this.lastSources = sources;
            }
        }
        delegate.publish(event);
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    /**
     * 本轮最后一次推出的来源；从未推出过则为空列表（**永不为 null**）。
     *
     * <p>返回的是不可变副本的语义：调用方只读，不需要防御性拷贝。
     */
    public List<Map<String, Object>> lastSources() {
        return lastSources;
    }
}
