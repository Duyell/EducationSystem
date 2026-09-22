-- =============================================================
-- 2026-09-22 会话与消息表迁移脚本（在 edujwxt 库执行一次）
-- 内容：ai_conversation（会话）+ ai_message（消息）
--
-- 需求（M2 计划 1.5 / 1.6）：
--   当前每轮对话只构造 [system, user] 两条消息、没有 sessionId —— 用户无法追问
--   （"那第二门呢？"），这是与"真 Agent"差距最大的一项。
--
-- 设计要点：
--   1. 会话与消息落 **MySQL**（而不是只放 Redis）：会话列表要可查、可审计、重启不丢；
--      "窗口裁剪"由 Spring AI 的 MessageWindowChatMemory 负责，本表只存事实；
--   2. `ai_message` 只存**对话可见**的消息（user / assistant 正文）。
--      工具调用与工具结果的原始报文留在 `ai_tool_audit`（那里有参数、结果、耗时、确认令牌），
--      两处各司其职，避免同一份报文两处存储、口径不一致；
--   3. 会话 id 用 **UUID 字符串**：要直接暴露给前端（SSE 事件、URL 参数），
--      自增整数容易被猜（越权探测）；归属校验仍在服务端强制执行（见 ConversationService）。
--
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS），不会破坏已有数据。
-- =============================================================

USE edujwxt;

CREATE TABLE IF NOT EXISTS `ai_conversation` (
  `id`            varchar(36)  NOT NULL                COMMENT '会话 id（UUID）',
  `user_id`       varchar(32)  NOT NULL                COMMENT '归属用户（学号/工号/用户名）',
  `role`          varchar(16)  NOT NULL                COMMENT '创建时的角色：student/teacher/admin',
  `title`         varchar(128) NULL DEFAULT NULL       COMMENT '会话标题（可用首条用户消息生成）',
  `message_count` int          NOT NULL DEFAULT 0      COMMENT '消息条数（便于列表展示，不参与判定）',
  `create_time`   datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   datetime     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conv_user` (`user_id`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI 会话（M2 多轮记忆）';

CREATE TABLE IF NOT EXISTS `ai_message` (
  `id`              bigint       NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(36)  NOT NULL                COMMENT '所属会话',
  `role`            varchar(16)  NOT NULL                COMMENT 'user / assistant',
  `content`         text         NULL                    COMMENT '消息正文',
  `create_time`     datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_msg_conv` (`conversation_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI 会话消息（面向用户可见的对话内容）';

-- 完成
