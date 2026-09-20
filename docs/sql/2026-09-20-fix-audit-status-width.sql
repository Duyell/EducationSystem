-- =============================================================
-- 2026-09-20 修复：ai_tool_audit.status 列宽不足
--
-- 问题：status 原为 varchar(16)，而实际常量 INVALID_ARGUMENTS 与
--       DUPLICATE_SKIPPED 均为 17 字符，写入时被截断成 INVALID_ARGUMENT /
--       DUPLICATE_SKIPPE。这会让按 status 的聚合统计把记录分到错误分组，
--       且「幂等命中」标记失真。
--
-- 发现方式：用本地 Ollama(qwen2.5:7b) 做真实 LLM 端到端验证时，
--           模型提交了越界评分触发参数校验路径，审计日志出现
--           「审计字段超长已截断: column=status, maxLen=16, actualLen=17」。
--           此前 39 项单测全部未覆盖该情况（测试只用了短状态值）。
--
-- 处置：列宽扩到 varchar(32)，并修正已被截断的历史数据。
-- 本脚本可重复执行。
-- =============================================================

USE edujwxt;

-- 1. 扩宽列（重复执行安全：已是 32 则不改）
SET @col_len = (
  SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'edujwxt' AND TABLE_NAME = 'ai_tool_audit' AND COLUMN_NAME = 'status'
);
SET @ddl = IF(@col_len < 32,
  'ALTER TABLE ai_tool_audit MODIFY COLUMN `status` varchar(32) NOT NULL COMMENT ''状态：SUCCESS/FAILED/DENIED/REJECTED_BY_USER/INVALID_ARGUMENTS/DUPLICATE_SKIPPED''',
  'SELECT ''status 列宽已足够，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 修正被截断的历史数据（仅当确实存在截断值时）
UPDATE ai_tool_audit SET status = 'INVALID_ARGUMENTS'
  WHERE status = 'INVALID_ARGUMENT';
UPDATE ai_tool_audit SET status = 'DUPLICATE_SKIPPED'
  WHERE status = 'DUPLICATE_SKIPPE';

-- 完成
