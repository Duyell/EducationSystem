package duyell.service.impl;

import com.duyell.AiConversation;
import com.duyell.AiMessage;
import duyell.mapper.AiConversationMapper;
import duyell.mapper.AiMessageMapper;
import duyell.service.ConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.util.List;
import java.util.UUID;

/**
 * {@link ConversationService} 实现。
 *
 * <p><b>三处刻意的选择</b>：
 * <ol>
 *   <li><b>归属校验在服务端</b>：会话 id 是 UUID 且会出现在前端 URL 与 SSE 事件里，
 *       天然可被修改。因此 {@link #requireOwned} 是所有按 id 读写的**唯一入口**，
 *       控制器不直接碰 mapper —— 只靠"前端只显示自己的列表"是挡不住改 id 的。</li>
 *   <li><b>消息只由一个地方写</b>：正文落库走 Spring AI 的 {@link ChatMemory}
 *       （底层是本项目的 {@code MybatisChatMemoryRepository}），
 *       服务层不自己写 insert，避免"框架写一份、业务再写一份"造成口径不一致。</li>
 *   <li><b>标题用首条用户消息生成</b>：会话列表里"新对话 / 新对话 / 新对话"没有任何信息量；
 *       只在标题还是默认值时改写，不覆盖用户/前端后来设过的标题。</li>
 * </ol>
 *
 * @author duyell
 */
@Slf4j
@Service
public class ConversationServiceImpl implements ConversationService {

    /** 未生成标题时的占位（前端也据此判断"是否还没说第一句话"） */
    public static final String DEFAULT_TITLE = "新对话";

    /** 自动标题的最大长度（按字符数，含省略号） */
    private static final int TITLE_MAX_LENGTH = 20;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final ChatMemory chatMemory;

    public ConversationServiceImpl(AiConversationMapper conversationMapper,
                                   AiMessageMapper messageMapper,
                                   ChatMemory chatMemory) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.chatMemory = chatMemory;
    }

    @Override
    public AiConversation create(String userId, String role, String title) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("会话归属用户不能为空");
        }
        AiConversation conversation = new AiConversation();
        conversation.setId(UUID.randomUUID().toString());
        conversation.setUserId(userId);
        conversation.setRole(role == null || role.isBlank() ? "student" : role);
        conversation.setTitle(title == null || title.isBlank() ? DEFAULT_TITLE : title.trim());
        conversationMapper.add(conversation);
        log.info("新建 AI 会话: id={}, user={}, role={}", conversation.getId(), userId, conversation.getRole());
        return conversationMapper.selectById(conversation.getId());
    }

    @Override
    public List<AiConversation> listForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("会话归属用户不能为空");
        }
        return conversationMapper.listByUser(userId);
    }

    @Override
    public List<AiMessage> messages(String conversationId, String userId) {
        requireOwned(conversationId, userId);
        return messageMapper.listByConversation(conversationId);
    }

    @Override
    @Transactional
    public boolean delete(String conversationId, String userId) {
        AiConversation conversation = requireOwned(conversationId, userId);
        // 先删消息再删会话：没有外键约束，顺序不影响一致性，但先删子表更符合直觉；
        // 同时清掉内存窗口，避免已删会话的记忆继续参与后续拼接。
        messageMapper.deleteByConversation(conversation.getId());
        conversationMapper.deleteById(conversation.getId());
        chatMemory.clear(conversation.getId());
        log.info("删除 AI 会话: id={}, user={}", conversation.getId(), userId);
        return true;
    }

    @Override
    public AiConversation requireOwned(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException("会话 id 不能为空");
        }
        AiConversation conversation = conversationMapper.selectById(conversationId);
        // 不存在与不属于本人给出**同一句提示**：区分开来等于告诉探测者"这个 id 存在"，
        // 而 id 是 UUID，本来就不该能被枚举出来。
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            log.warn("会话归属校验失败: id={}, 请求人={}, 存在={}",
                    conversationId, userId, conversation != null);
            throw new BusinessException("会话不存在或无权访问");
        }
        return conversation;
    }

    @Override
    public void appendUserMessage(String conversationId, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        chatMemory.add(conversationId, List.of(new UserMessage(content)));
        applyAutoTitle(conversationId, content);
    }

    @Override
    public void appendAssistantMessage(String conversationId, String content) {
        if (content == null || content.isBlank()) {
            // 本轮没有任何正文（例如模型只调了工具就结束）：不留空消息，
            // 否则历史里会出现空 assistant 轮次，下一轮拼接时是噪声。
            return;
        }
        chatMemory.add(conversationId, List.of(new AssistantMessage(content)));
    }

    /** 会话还没有真正的标题时，用首条用户消息的前若干字取名 */
    private void applyAutoTitle(String conversationId, String firstUserMessage) {
        AiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            // 会话可能刚被删除（用户点了删除又恰好有在途对话）：不是错误，忽略即可
            log.debug("会话已不存在，跳过标题生成: id={}", conversationId);
            return;
        }
        String current = conversation.getTitle();
        if (current != null && !current.isBlank() && !DEFAULT_TITLE.equals(current)) {
            return;     // 已有标题，不覆盖
        }
        conversationMapper.updateTitle(conversationId, summarize(firstUserMessage));
    }

    /**
     * 由首条用户消息生成标题。
     *
     * <p>换行会破坏列表布局，统一压成空格；按 code point 截断，
     * 避免把 emoji 的代理对切成两半（半个代理对会让前端显示成乱码方块）。
     */
    private static String summarize(String message) {
        String compact = message.replaceAll("\\s+", " ").trim();
        int codePoints = compact.codePointCount(0, compact.length());
        if (codePoints <= TITLE_MAX_LENGTH) {
            return compact;
        }
        int end = compact.offsetByCodePoints(0, TITLE_MAX_LENGTH - 1);
        return compact.substring(0, end) + "…";
    }
}
