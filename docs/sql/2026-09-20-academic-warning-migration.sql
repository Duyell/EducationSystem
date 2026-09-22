-- =============================================================
-- 2026-09-20 学业预警表迁移脚本（在 edujwxt 库执行一次）
-- 内容：新增 academic_warning —— 学业预警的「已读水位线」记录
--
-- 需求（作者 2026-09-20 审计 JW-01 时确定）：
--   只做学业预警（不做留级/退学等自动化）；学生满足条件后**登录时弹一条通知**；
--   具体事宜由辅导员约谈；严重情况由管理员修改学生状态。
--   通知方式定为：**只弹一次，学生可标记已读**。
--
-- 语义（重要）：
--   一张表只记「学生已确认过的预警水位线」，不记未读状态——
--   GET 接口**不写库**（避免读接口产生写副作用），
--   学生点"我知道了"时（POST /academic-warning/my/read）才插入一行，记录**当时的**：
--     failed_credits（未通过学分累计）、failed_course_count、threshold（阈值快照）、read_at。
--   之后再次登录时：当前未通过学分 > 已记录的最大水位线 → 才再次提示（即"情况变严重才再弹"）；
--   未超过则不再打扰。阈值本身取自配置，快照存下来便于回溯"当时按什么标准预警"。
--
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS），不会破坏已有数据。
-- =============================================================

USE edujwxt;

CREATE TABLE IF NOT EXISTS `academic_warning` (
  `id`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `student_id`          varchar(32)  NOT NULL                COMMENT '学号',
  `failed_credits`      decimal(6,2) NOT NULL                COMMENT '确认时的未通过学分累计（水位线）',
  `failed_course_count` int          NOT NULL DEFAULT 0      COMMENT '确认时的未通过课程门数',
  `threshold`           decimal(6,2) NOT NULL                COMMENT '确认时的预警阈值（快照）',
  `read_at`             datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '学生确认（已读）时间',
  `create_time`         datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 同一学生的同一水位线只记一次：重复"标记已读"是幂等的
  UNIQUE KEY `uk_warning_student_credits` (`student_id`, `failed_credits`),
  KEY `idx_warning_student` (`student_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '学业预警已读水位线（JW-01 §5）';

-- 完成
