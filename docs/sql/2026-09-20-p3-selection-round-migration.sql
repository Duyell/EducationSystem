-- =============================================================
-- 2026-09-20 P3 迁移：选课轮次 + 选课/补退选
--
-- 依据文档：docs/教务业务扩展设计.md（§2 业务规则、§3.2(7)(8)、§5 P3）
--
-- 本脚本内容：
--   1. selection_round       选课轮次（管理员控制开关 + 选课/补退选时间窗）
--   2. selection_round_scope 轮次适用范围（年级/专业/学院，NULL=不限）
--   3. course_selection 增加 round_id（选课时落在哪个轮次，便于追溯）
--   4. 种子：三个轮次，分别覆盖「开放中」「补退选（只能退）」「未开启」三种状态
--
-- ⚠️ 可重复执行（建表 IF NOT EXISTS；加列/加索引查 information_schema；种子判重）
--
-- ⚠️ 用户明确的规则（不得改动）：
--   · 只有管理员**开启选课**后学生才能选；否则**只能看**
--   · 选课持续期间**可以退**，否则要等**补退选**期间才能退
--
-- ⚠️【假设】补退选窗口只用于**退选**：用户原话是"否则要等补退选期间才能退"，
--    只提到退；且本表字段名为 drop_start/drop_end（而不是 adjust_*）。
--    若实际需要"补选"（补退选期间也能选），改 SelectionRoundService 里
--    canSelect 的判断即可（把 drop 窗口一并算作可选），是一行改动。
-- =============================================================

USE edujwxt;

-- ---------- 1. selection_round 选课轮次 ----------
CREATE TABLE IF NOT EXISTS `selection_round` (
  `id`           int          NOT NULL AUTO_INCREMENT,
  `round_name`   varchar(64)  NOT NULL                COMMENT '如：2024-2025-1 第一轮选课',
  `term`         varchar(64)  NOT NULL                COMMENT '适用学期',
  `select_start` datetime     NOT NULL                COMMENT '选课开放开始',
  `select_end`   datetime     NOT NULL                COMMENT '选课开放结束',
  `drop_start`   datetime     NULL DEFAULT NULL        COMMENT '补退选开始（可空）',
  `drop_end`     datetime     NULL DEFAULT NULL        COMMENT '补退选结束（可空）',
  `status`       tinyint      NOT NULL DEFAULT 0      COMMENT '1=开启 0=关闭（只有开启时学生才能选）',
  `max_credits`  decimal(5,1) NULL DEFAULT NULL        COMMENT '本轮学分上限，可空=不限',
  `create_time`  datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`  datetime     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_round_term_status`(`term` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '选课轮次（管理员控制开关）';

-- ---------- 2. selection_round_scope 轮次适用范围 ----------
-- 不同年级/专业的选课时间通常不同（如毕业班优先），故限定范围。
-- 本轮只做"限定范围"，**不做优先级排序**（见设计文档 §3.2(8)）。
CREATE TABLE IF NOT EXISTS `selection_round_scope` (
  `id`         int         NOT NULL AUTO_INCREMENT,
  `round_id`   int         NOT NULL              COMMENT '关联 selection_round.id',
  `grade`      varchar(32) NULL DEFAULT NULL     COMMENT '限定年级，NULL=不限',
  `major_id`   int         NULL DEFAULT NULL     COMMENT '限定专业，NULL=不限',
  `college_id` int         NULL DEFAULT NULL     COMMENT '限定学院，NULL=不限',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_scope_round`(`round_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '选课轮次适用范围';

-- ---------- 3. course_selection 增加 round_id ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA='edujwxt' AND TABLE_NAME='course_selection' AND COLUMN_NAME='round_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE course_selection ADD COLUMN round_id int NULL DEFAULT NULL COMMENT ''选课时命中的轮次；历史数据为空'' AFTER student_id',
  'SELECT ''course_selection.round_id 已存在，跳过'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA='edujwxt' AND TABLE_NAME='course_selection' AND INDEX_NAME='idx_selection_round');
SET @ddl := IF(@idx = 0,
  'CREATE INDEX idx_selection_round ON course_selection(round_id)',
  'SELECT ''idx_selection_round 已存在，跳过'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 4. 种子轮次 ----------
-- 时间窗用 NOW() 相对计算，而不是写死日期：写死的话过一阵子演示数据就"全部过期"，
-- 新装库的人会看到"所有轮次都已结束"，以为功能坏了。
-- 与 edujwxt.sql / seed_data.sql 的种子保持一致。

-- 4.1 开放中：2024-2025-1（该学期有 7 门课），学分上限 30
INSERT INTO `selection_round` (`round_name`, `term`, `select_start`, `select_end`, `status`, `max_credits`)
SELECT '2024-2025-1 第一轮选课', '2024-2025-1',
       DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY), 1, 30.0
WHERE NOT EXISTS (SELECT 1 FROM selection_round WHERE round_name = '2024-2025-1 第一轮选课');

-- 4.2 补退选（只能退不能选）：选课窗口已过，退课窗口正在开放
INSERT INTO `selection_round` (`round_name`, `term`, `select_start`, `select_end`, `drop_start`, `drop_end`, `status`, `max_credits`)
SELECT '2024-2025-2 补退选', '2024-2025-2',
       DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY),
       DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 10 DAY), 1, 30.0
WHERE NOT EXISTS (SELECT 1 FROM selection_round WHERE round_name = '2024-2025-2 补退选');

-- 4.3 未开启（管理员还没点开）：窗口在时间上有效，但 status=0，学生只能看
INSERT INTO `selection_round` (`round_name`, `term`, `select_start`, `select_end`, `status`, `max_credits`)
SELECT '2025-2026-1 第一轮选课（未开启）', '2025-2026-1',
       DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY), 0, 30.0
WHERE NOT EXISTS (SELECT 1 FROM selection_round WHERE round_name = '2025-2026-1 第一轮选课（未开启）');

-- 4.4 给"未开启"那条配一个范围示例，便于管理员页面演示范围编辑
SET @closedRound := (SELECT id FROM selection_round WHERE round_name = '2025-2026-1 第一轮选课（未开启）' LIMIT 1);
INSERT INTO `selection_round_scope` (`round_id`, `grade`, `major_id`, `college_id`)
SELECT @closedRound, '2023', NULL, NULL
WHERE @closedRound IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM selection_round_scope WHERE round_id = @closedRound);

-- ---------- 完成 ----------
SELECT id, round_name, term, status, max_credits,
       (NOW() BETWEEN select_start AND select_end) AS in_select_window,
       (drop_start IS NOT NULL AND NOW() BETWEEN drop_start AND drop_end) AS in_drop_window
FROM selection_round ORDER BY id;
