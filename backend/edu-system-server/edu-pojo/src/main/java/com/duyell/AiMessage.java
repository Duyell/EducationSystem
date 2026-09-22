package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 会话消息（表 {@code ai_message}）。
 *
 * <p>只存**面向用户可见**的对话内容（user / assistant 正文）；
 * 工具调用与工具结果的原始报文留在 {@code ai_tool_audit}
 * （那里有参数、结果、耗时、确认令牌），两处各司其职。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private Long id;

    private String conversationId;

    /** user / assistant */
    private String role;

    private String content;

    private LocalDateTime createTime;
}
