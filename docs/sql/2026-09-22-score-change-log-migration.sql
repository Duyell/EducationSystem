-- =============================================================
-- 2026-09-22 成绩变更日志迁移脚本（在 edujwxt 库执行一次）
-- 内容：新增 score_change_log —— 成绩的新增/修改/删除留痕
--
-- 需求（作者 2026-09-22 拍板"加"）：
--   成绩是**可申诉**的敏感数据。此前只有"经 AI 助手"的变更会写 ai_tool_audit，
--   教师/管理员在界面直接改成绩**什么都不记** —— 学生质疑"我这门课怎么从 90 变成 60"时查不到任何依据。
--
-- 设计要点：
--   1. 挂钩点只有一处：ScoreServiceImpl 的 add/update/delete
--      （这是全部成绩写入的必经之路，界面与 AI 两条路径都会自动被记上）；
--   2. 记**改前 → 改后**的完整快照（平时/考试/补考/总分/是否通过），而不是只记"改过"；
--   3. 记**来源**（UI 界面/接口，还是 AI 智能助手）与**操作人**：
--      由 duyell.audit.ChangeContext 在边界处设置（ScoreController 与 ToolRegistry.executeForRole）；
--   4. 只增加记录，**不改变任何判定逻辑**（成绩计算、及格判定、绩点都不受影响）。
--
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS），不会破坏已有数据。
-- =============================================================

USE edujwxt;

CREATE TABLE IF NOT EXISTS `score_change_log` (
  `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `operator_id`      varchar(32)  NOT NULL                COMMENT '操作人（学号/工号/用户名）',
  `operator_role`    varchar(16)  NULL DEFAULT NULL       COMMENT '操作人角色：student/teacher/admin',
  `source`           varchar(16)  NOT NULL DEFAULT 'UI'   COMMENT '来源：UI（界面或接口）/ AI（智能助手）',
  `operation`        varchar(16)  NOT NULL                COMMENT '操作：INSERT/UPDATE/DELETE',
  `score_id`         int          NULL DEFAULT NULL       COMMENT '成绩记录 id（删除后仍保留，便于回溯）',
  `course_id`        int          NOT NULL                COMMENT '课程 id（一次开课）',
  `student_id`       varchar(32)  NOT NULL                COMMENT '学号',
  `before_usual`     decimal(6,3) NULL DEFAULT NULL       COMMENT '改前·平时成绩',
  `before_exam`      decimal(6,3) NULL DEFAULT NULL       COMMENT '改前·考试成绩',
  `before_makeup`    decimal(6,3) NULL DEFAULT NULL       COMMENT '改前·补考成绩',
  `before_total`     decimal(6,3) NULL DEFAULT NULL       COMMENT '改前·总成绩',
  `before_passed`    tinyint      NULL DEFAULT NULL       COMMENT '改前·是否通过（1/0）',
  `after_usual`      decimal(6,3) NULL DEFAULT NULL       COMMENT '改后·平时成绩',
  `after_exam`       decimal(6,3) NULL DEFAULT NULL       COMMENT '改后·考试成绩',
  `after_makeup`     decimal(6,3) NULL DEFAULT NULL       COMMENT '改后·补考成绩',
  `after_total`      decimal(6,3) NULL DEFAULT NULL       COMMENT '改后·总成绩',
  `after_passed`     tinyint      NULL DEFAULT NULL       COMMENT '改后·是否通过（1/0）',
  `create_time`      datetime     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_score_log_student`  (`student_id`, `create_time`),
  KEY `idx_score_log_course`   (`course_id`, `create_time`),
  KEY `idx_score_log_operator` (`operator_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '成绩变更日志（JW-09 §4.5）';

-- 完成
