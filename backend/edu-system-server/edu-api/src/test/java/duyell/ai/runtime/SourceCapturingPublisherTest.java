package duyell.ai.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceCapturingPublisher} 的单元测试（M3 来源卡片持久化）。
 *
 * <p>它要证的不变量有两条，都是"静默失败"型的——错了不会报错，只会让卡片刷新后消失：
 * <ol>
 *   <li><b>事件必须照常转发</b>：装饰器只该"顺手抄一份"，不能把事件吞掉。
 *       吞掉的后果是前端连卡片都没有，比不落库更糟；</li>
 *   <li><b>取到的是最后一次来源</b>：一轮里模型可能先自己检索、之后服务端又强制注入，
 *       落库的是这一轮最终的助手消息，它的依据就是最后那批。
 *       若保留第一批，卡片会列出"检索过但没用上"的条款（看起来像答非所问）。</li>
 * </ol>
 */
class SourceCapturingPublisherTest {

    /** 记录型下游出口：转发是否发生、顺序如何，全看它 */
    private static final class Recording implements AgentEventPublisher {
        final List<AgentEvent> events = new ArrayList<>();
        int completes = 0;

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public void complete() {
            completes++;
        }
    }

    private static Map<String, Object> source(String docId, String citation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docId", docId);
        item.put("citation", citation);
        return item;
    }

    @Test
    void eventsAreForwardedUntouched() {
        Recording downstream = new Recording();
        SourceCapturingPublisher publisher = new SourceCapturingPublisher(downstream);

        AgentEvent status = AgentEvent.status("正在思考");
        publisher.publish(status);
        publisher.publish(AgentEvent.sources(List.of(source("03", "重修与补考办法 3. 补考"))));
        publisher.complete();

        assertEquals(2, downstream.events.size(), "一个事件都不能被吞掉");
        assertSame(status, downstream.events.get(0));
        assertEquals(AgentEventType.SOURCES, downstream.events.get(1).type());
        assertEquals(1, downstream.completes, "complete 也要透传，否则 SSE 流不结束");
    }

    @Test
    void lastSourcesWin() {
        Recording downstream = new Recording();
        SourceCapturingPublisher publisher = new SourceCapturingPublisher(downstream);

        publisher.publish(AgentEvent.status("无来源的状态事件不该影响结果"));
        publisher.publish(AgentEvent.sources(List.of(source("01", "第一次-模型自己检索的"))));
        publisher.publish(AgentEvent.sources(List.of(
                source("03", "重修与补考办法 3. 补考"),
                source("05", "成绩构成与绩点换算办法 3. 及格判定"))));

        List<Map<String, Object>> captured = publisher.lastSources();
        assertEquals(2, captured.size(), "应是最后一次那批（服务端强制注入的）");
        assertEquals("重修与补考办法 3. 补考", captured.get(0).get("citation"));
    }

    /** 从没发过来源时给空列表而不是 null：调用方（落库）不必到处判空 */
    @Test
    void noSourcesMeansEmptyListNotNull() {
        SourceCapturingPublisher publisher = new SourceCapturingPublisher(new Recording());

        publisher.publish(AgentEvent.status("普通回答，没有依据"));
        publisher.publish(AgentEvent.token("你好"));

        assertTrue(publisher.lastSources().isEmpty());
    }
}
