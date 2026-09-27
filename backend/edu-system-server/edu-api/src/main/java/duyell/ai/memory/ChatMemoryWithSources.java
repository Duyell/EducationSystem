package duyell.ai.memory;

import org.springframework.ai.chat.memory.ChatMemory;

import java.util.List;
import java.util.Map;

/**
 * 带**来源**的会话记忆写入扩展点（M3 来源卡片持久化）。
 *
 * <p><b>为什么需要它</b>：Spring AI 的 {@link ChatMemory#add} 只接受框架的 {@code Message}，
 * 而 {@code AssistantMessage} 里没有"这条回答依据了哪几条制度"的位置。出处本可以只当作
 * 一次性事件推给前端，但那样**刷新页面卡片就没了**——而"依据"是回答的一部分事实，不是转场动画。
 *
 * <p><b>为什么是接口而不是直接注入 {@code MybatisChatMemory}</b>：调用方（会话服务）只应依赖
 * "谁能写入一条带出处的助手消息"，而不是依赖某个具体存储实现；同时它仍是 {@link ChatMemory}，
 * 因此框架自动配置的 {@code @ConditionalOnMissingBean} 退让逻辑不受影响。
 *
 * @author duyell
 */
public interface ChatMemoryWithSources extends ChatMemory {

    /**
     * 追加一条**带出处**的助手消息；无出处时应当与 {@link #add} 等价。
     *
     * <p>实现方需自己保证"消息只有一个写入入口"（见 {@code MybatisChatMemory} 类注释）：
     * 这个方法必须复用 {@link #add} 那条写入路径，而不是另写一条 insert。
     *
     * @param sources 出处，每项形如 {@code {docId, docTitle, section, citation}}；
     *                为 {@code null}/空表示无出处
     */
    void addAssistant(String conversationId, String content, List<Map<String, Object>> sources);
}
