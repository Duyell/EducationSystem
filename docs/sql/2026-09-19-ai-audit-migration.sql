-- =============================================================
-- 2026-09-19 M1 审计表迁移脚本（在 edujwxt 库执行一次）
-- 内容：新增 ai_tool_audit —— AI 工具调用的审计留痕
-- 目的：
--   1. 每次工具调用（成功/失败/越权拒绝/用户拒绝）都留痕，可追溯；
--   2. request_id 唯一键为后续「幂等保护（阶段 0.6）」打基础；
--   3. 为后续可观测（阶段 4）提供数据源：耗时、失败率、按工具聚合。
--
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS），不会破坏已有数据。
-- =============================================================

USE edujwxt;

CREATE TABLE IF NOT EXISTS `ai_tool_audit` (
  `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`      varchar(32)  NOT NULL                COMMENT '调用者标识（学号/工号/用户名）',
  `role`         varchar(16)  NOT NULL                COMMENT '调用者角色：student/teacher/admin',
  `session_id`   varchar(64)  NULL DEFAULT NULL       COMMENT '会话标识（阶段 1 引入多轮记忆后启用）',
  `tool_name`    varchar(64)  NOT NULL                COMMENT '工具名',
  `risk_level`   varchar(16)  NOT NULL                COMMENT '风险等级：READ_ONLY/WRITE/DANGEROUS',
  `args_json`    text         NULL                    COMMENT '调用参数（JSON）',
  `result_json`  text         NULL                    COMMENT '执行结果（JSON，已脱敏；越权时记录拒绝原因）',
  `status`       varchar(16)  NOT NULL                COMMENT '状态：SUCCESS/FAILED/DENIED/REJECTED_BY_USER',
  `error_msg`    text         NULL                    COMMENT '失败或拒绝的详情（仅服务端与审计可见）',
  `confirm_id`   varchar(64)  NULL DEFAULT NULL       COMMENT '危险操作确认令牌（经人工确认的操作可关联）',
  `request_id`   varchar(64)  NULL DEFAULT NULL       COMMENT '幂等键；唯一约束保证同一请求不重复落库',
  `duration_ms`  bigint       NULL DEFAULT NULL       COMMENT '执行耗时（毫秒）',
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_audit_request` (`request_id`),
  KEY `idx_ai_audit_user`   (`user_id`, `created_at`),
  KEY `idx_ai_audit_tool`   (`tool_name`, `created_at`),
  KEY `idx_ai_audit_status` (`status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'AI 工具调用审计日志';

-- 完成
