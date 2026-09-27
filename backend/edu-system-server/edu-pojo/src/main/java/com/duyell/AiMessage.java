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
 * <p>{@code sourcesJson}（M3 来源卡片）是这条回答"依据了哪几条制度"的事实记录，
 * 与正文一起落库——否则刷新页面后卡片消失，用户会以为功能坏了。
 * 这里**只存原始 JSON 字符串**，不解析：本模块（edu-pojo）是纯数据载体，
 * 不认识 JSON 库；序列化/解析由 {@code duyell.ai.memory.SourceJson} 负责。
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

    /**
     * 来源卡片出处的原始 JSON（形如 {@code [{"docId":"03","citation":"..."}]}）。
     *
     * <p>只对 assistant 消息有值；user 消息与 M3 之前的历史数据为 {@code null}
     * （{@code null} 的含义是"当时没有记录"，与"有记录但为空"区分开）。
     */
    private String sourcesJson;

    private LocalDateTime createTime;
}
