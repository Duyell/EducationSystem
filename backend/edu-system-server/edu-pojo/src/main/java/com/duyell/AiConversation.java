package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 会话（表 {@code ai_conversation}，M2 多轮记忆）。
 *
 * <p>id 是 UUID 字符串：要直接暴露给前端（SSE 事件、URL 参数），自增整数容易被猜；
 * 归属校验在服务端强制执行（见 {@code ConversationService}）。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiConversation {

    private String id;

    /** 归属用户（学号/工号/用户名） */
    private String userId;

    /** 创建时的角色：student/teacher/admin（决定用哪套 system prompt） */
    private String role;

    private String title;

    /** 消息条数：仅用于列表展示，不参与任何判定 */
    private Integer messageCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
