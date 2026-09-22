package duyell.ai.memory;

import com.duyell.AiMessage;
import duyell.mapper.AiConversationMapper;
import duyell.mapper.AiMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 多轮记忆的持久化实现（MySQL，M2 计划 1.5）。
 *
 * <p><b>为什么不用框架自带的 {@code MessageWindowChatMemory}</b>：
 * 它的 {@code add(conversationId, messages)} 并不是"追加新消息"，而是
 * <pre>
 *   existing = repository.findByConversationId(id)
 *   merged   = existing + messages              // 合并
 *   merged   = merged 裁剪到 maxMessages        // 窗口裁剪
 *   repository.saveAll(id, merged)              // 保存【整个窗口】
 * </pre>
 * （反编译 {@code spring-ai-model:1.0.9} 的字节码可逐一核对）。
 * 也就是说 {@code ChatMemoryRepository.saveAll} 的语义是"用这一份**替换**该会话的全部消息"
 * ——对内存实现（{@code InMemoryChatMemoryRepository} 直接 {@code map.put}）成立，
 * 但落到 MySQL 上只有两条路，且都不行：
 * <ol>
 *   <li>照语义**替换**（先删后插）：能去重，但 {@code ai_message} 只剩最近窗口，
 *       用户在界面上往上翻，早先的消息就永久没了——"会话可查、可审计"直接失效；</li>
 *   <li>当成**追加**插入：每轮都把整个窗口再写一遍，消息成倍膨胀（第 3 轮 6 条 → 12 条）。</li>
 * </ol>
 *
 * <p><b>本实现取第三条路</b>：{@code ai_message} 是**只追加的完整对话记录**（界面历史、
 * 审计留痕都读它），模型上下文则是"从这份记录里取最近 N 条"。
 * 于是各方的职责是：
 * <ul>
 *   <li>写入只有一个入口（{@link #add}），不会出现"框架写一份、业务再写一份"；</li>
 *   <li>窗口规则只有一处实现（一条 SQL 的 {@code limit}，见
 *       {@link AiMessageMapper#listRecent}）；</li>
 *   <li>越界的旧消息留在库里（界面能翻到、审计能查），只是不进模型上下文。</li>
 * </ul>
 * 代价是没沿用框架的窗口类——但窗口裁剪本身只是"取最近 N 条"，
 * 为此付出"丢历史"或"消息翻倍"的代价并不划算。
 *
 * <p>只存 {@code user}/{@code assistant} 正文：工具调用与工具结果的原始报文留在
 * {@code ai_tool_audit}（参数、结果、耗时、确认令牌都在那儿），
 * 同一份报文两处存储必然口径不一致，而"多轮追问"需要的是对话本身。
 *
 * @author duyell
 */
@Slf4j
@Component
public class MybatisChatMemory implements ChatMemory {

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;

    /** 进模型上下文的**消息条数**上限（一问一答算两条）。默认 20 条 ≈ 10 轮 */
    private final int maxMessages;

    public MybatisChatMemory(AiMessageMapper messageMapper,
                             AiConversationMapper conversationMapper,
                             @Value("${ai.memory.max-messages:20}") int maxMessages) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        // 至少保留一问一答：上限小到 1 会让"追问"永远拿不到上下文，且框架/调用方都难察觉
        this.maxMessages = Math.max(2, maxMessages);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank() || messages == null) {
            return;
        }
        int saved = 0;
        for (Message message : messages) {
            String role = message == null ? null : roleOf(message);
            if (role == null) {
                continue;   // system / tool 消息不落这张表（见类注释）
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;   // 空正文没有记忆价值
            }
            AiMessage row = new AiMessage();
            row.setConversationId(conversationId);
            row.setRole(role);
            row.setContent(text);
            messageMapper.add(row);
            conversationMapper.increaseMessageCount(conversationId);
            saved++;
        }
        log.debug("会话记忆追加: conversation={}, 请求={} 条, 实际落库={} 条",
                conversationId, messages.size(), saved);
    }

    @Override
    public List<Message> get(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        // 先按 id 倒序取最近 N 条（走索引，不必把整个会话读进内存），再反转成时间正序
        List<AiMessage> recent = messageMapper.listRecent(conversationId, maxMessages);
        if (recent == null || recent.isEmpty()) {
            return List.of();
        }
        List<AiMessage> ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);
        return ordered.stream()
                .map(MybatisChatMemory::toMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        messageMapper.deleteByConversation(conversationId);
    }

    private static String roleOf(Message message) {
        MessageType type = message.getMessageType();
        if (type == MessageType.USER) {
            return AiMessage.ROLE_USER;
        }
        if (type == MessageType.ASSISTANT) {
            return AiMessage.ROLE_ASSISTANT;
        }
        return null;
    }

    private static Message toMessage(AiMessage row) {
        if (row.getContent() == null || row.getContent().isBlank()) {
            return null;
        }
        if (AiMessage.ROLE_USER.equals(row.getRole())) {
            return new UserMessage(row.getContent());
        }
        if (AiMessage.ROLE_ASSISTANT.equals(row.getRole())) {
            return new AssistantMessage(row.getContent());
        }
        log.warn("会话消息角色未知，已跳过: conversation={}, role={}", row.getConversationId(), row.getRole());
        return null;
    }
}
