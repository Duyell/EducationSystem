package duyell.service;

import com.duyell.AiConversation;
import com.duyell.AiMessage;

import java.util.List;

/**
 * AI 会话管理（M2 计划 1.6）。
 *
 * <p><b>安全要点</b>：会话 id 会出现在前端 URL 与 SSE 事件里，天然可被改，
 * 因此**每一个按 id 的读写都必须校验归属**（{@link #requireOwned}）。
 * 这与本项目其他模块的反越权做法一致：不靠"前端只显示自己的"，而是服务端强制。
 *
 * @author duyell
 */
public interface ConversationService {

    /**
     * 新建会话。
     *
     * @param userId 归属用户（取自登录态，不接受入参）
     * @param role   角色（决定之后用哪套系统提示词）
     * @param title  标题，可空（空则用首条用户消息的前若干字生成）
     */
    AiConversation create(String userId, String role, String title);

    /** 某人的会话列表（最近更新在前） */
    List<AiConversation> listForUser(String userId);

    /** 某会话的消息（按时间正序）——**会校验归属** */
    List<AiMessage> messages(String conversationId, String userId);

    /**
     * 删除会话及其消息——**会校验归属**。
     *
     * @return 是否删除成功
     */
    boolean delete(String conversationId, String userId);

    /**
     * 归属校验：不属于该用户就拒绝。
     *
     * @throws utils.BusinessException 会话不存在或不属于该用户
     */
    AiConversation requireOwned(String conversationId, String userId);

    /** 追加一条用户消息（同时在会话计数上 +1） */
    void appendUserMessage(String conversationId, String content);

    /** 追加一条助手消息（同时在会话计数上 +1） */
    void appendAssistantMessage(String conversationId, String content);
}
