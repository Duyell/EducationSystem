-- =============================================================
-- 2026-09-27 消息来源（来源卡片）持久化迁移脚本（在 edujwxt 库执行一次）
-- 内容：给 `ai_message` 增加 `sources_json` 列
--
-- 需求（M3 收尾）：来源卡片的出处此前只走 SSE 事件，**不落库** —— 刷新页面后卡片消失，
--   底部历史只剩正文，用户会以为功能坏了。出处是"这条回答依据了哪几条制度"的事实记录，
--   属于消息本身的一部分，应当与正文一起持久化。
--
-- 设计要点：
--   1. 存**原始 JSON 字符串**（形如 [{"docId":"03","docTitle":"...","section":"...","citation":"..."}]），
--      不清洗、不拆成多行：出处的唯一消费者是前端卡片（原样渲染），
--      而"一条消息 N 条出处"拆表会带来一个只被读一次的关联表；
--   2. 只对 **assistant** 消息有值；user 消息与历史数据为 NULL（老数据不回溯，NULL 即"当时没记录"）；
--   3. 用 TEXT 而不是 varchar：条款出处虽短，但一旦将来塞进条款摘要，varchar 会静默截断。
--
-- 说明：本脚本可重复执行（先查 information_schema 再决定是否 ALTER），不会破坏已有数据。
-- =============================================================

USE edujwxt;

SET @col_exists := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_message'
    AND COLUMN_NAME = 'sources_json'
);

SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `ai_message` ADD COLUMN `sources_json` text NULL COMMENT ''来源卡片出处（JSON 数组：docId/docTitle/section/citation）'' AFTER `content`',
  'DO 0');

PREPARE migrate_sources FROM @ddl;
EXECUTE migrate_sources;
DEALLOCATE PREPARE migrate_sources;

-- 完成
