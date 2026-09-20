-- =============================================================
-- 2026-09-20 P1 迁移：培养计划 + 绩点规则 + 判断类功能的地基
--
-- 依据文档：docs/教务业务扩展设计.md（§3.2 新增表、§3.3 改造现有表）
--
-- 本脚本内容：
--   1. course 增加 course_code（课程代码）—— 判断类功能的地基
--   2. score 增加 makeup_score（补考）、passed（是否通过），并把精度扩到 (6,3)
--   3. student 增加 status（学籍状态，本轮只加字段不做逻辑）
--   4. 新增 training_plan / plan_course / gpa_rule 三张表
--   5. 回填现有 12 条课程的课程代码
--   6. 种子：绩点规则 1 条 + 示例培养计划
--
-- ⚠️ 可重复执行（用 information_schema 判断列/表是否已存在）
--
-- ⚠️ 关于示例培养计划的重要说明：
--   现有 course 表只有 2024-2025 两个学期的 12 门课，缺少完整 4 年课程。
--   因此种子培养计划**不是**真实教学计划，仅用于让 P1 的学分审核链路可演示、可测试。
--   真实计划应由管理员在页面上维护（P1 后续任务）。
-- =============================================================

USE edujwxt;

-- ---------- 1. course 增加 course_code ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA='edujwxt' AND TABLE_NAME='course' AND COLUMN_NAME='course_code');
SET @ddl := IF(@col = 0,
  'ALTER TABLE course ADD COLUMN course_code varchar(32) NULL COMMENT ''课程代码：同一门课的所有开课行共用，培养计划/已修判定/补考关联均以此为准'' AFTER id',
  'SELECT ''course.course_code 已存在，跳过'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ⚠️ 刻意**不加唯一约束**：同一门课不同学期/不同教师开课会有多行，共用同一 code
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA='edujwxt' AND TABLE_NAME='course' AND INDEX_NAME='idx_course_code');
SET @ddl := IF(@idx = 0,
  'CREATE INDEX idx_course_code ON course(course_code)',
  'SELECT ''idx_course_code 已存在，跳过'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 2. score 增加补考 / 通过标记 + 精度调整 ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA='edujwxt' AND TABLE_NAME='score' AND COLUMN_NAME='makeup_score');
SET @ddl := IF(@col = 0,
  'ALTER TABLE score ADD COLUMN makeup_score decimal(6,3) NULL DEFAULT NULL COMMENT ''补考成绩；通过则统一按 60 分计入绩点'' AFTER total_score',
  'SELECT ''score.makeup_score 已存在，跳过'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA='edujwxt' AND TABLE_NAME='score' AND COLUMN_NAME='passed');
SET @ddl := IF(@col = 0,
  'ALTER TABLE score ADD COLUMN passed tinyint NULL DEFAULT NULL COMMENT ''是否通过（含补考）：1=通过 0=未通过；派生字段，只由 ScoreService 维护'' AFTER makeup_score',
  'SELECT ''score.passed 已存在，跳过'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 精度 decimal(5,1) -> decimal(6,3)：绩点例子里有 75.76 / 87.365，1 位小数存不下
ALTER TABLE score
  MODIFY COLUMN usual_score decimal(6,3) NULL DEFAULT 0.000 COMMENT '平时成绩',
  MODIFY COLUMN exam_score  decimal(6,3) NULL DEFAULT 0.000 COMMENT '考试成绩',
  MODIFY COLUMN total_score decimal(6,3) NULL DEFAULT 0.000 COMMENT '总成绩 = 平时*0.4 + 考试*0.6';

-- 回填 passed（历史数据：总成绩 >= 60 即通过）
UPDATE score SET passed = CASE WHEN total_score >= 60 THEN 1 ELSE 0 END WHERE passed IS NULL;

-- ---------- 3. student 增加学籍状态（本轮只加字段） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA='edujwxt' AND TABLE_NAME='student' AND COLUMN_NAME='status');
SET @ddl := IF(@col = 0,
  'ALTER TABLE student ADD COLUMN status varchar(16) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''学籍状态：ACTIVE/SUSPENDED/WITHDRAWN（本轮只加字段，逻辑后续）''',
  'SELECT ''student.status 已存在，跳过'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 4. 新增三张表 ----------

-- 培养计划：按「专业 + 年级」分版（老生沿用入学年级版本）
CREATE TABLE IF NOT EXISTS `training_plan` (
  `id`               int          NOT NULL AUTO_INCREMENT,
  `plan_name`        varchar(128) NOT NULL                COMMENT '如：计算机科学与技术 2023 级培养计划',
  `major_id`         int          NOT NULL                COMMENT '关联 major.id',
  `grade`            varchar(32)  NOT NULL                COMMENT '适用年级（决定版本），如 2023',
  `total_credits`    decimal(5,1) NOT NULL DEFAULT 0.0    COMMENT '毕业总学分要求',
  `required_credits` decimal(5,1) NOT NULL DEFAULT 0.0    COMMENT '必修学分要求',
  `elective_credits` decimal(5,1) NOT NULL DEFAULT 0.0    COMMENT '选修学分要求',
  `status`           tinyint      NOT NULL DEFAULT 1      COMMENT '1=启用 0=停用',
  `remark`           varchar(255) NULL DEFAULT NULL,
  `create_time`      datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      datetime     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_plan_major_grade`(`major_id` ASC, `grade` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '培养计划（按专业+年级分版）';

-- 培养计划课程明细
CREATE TABLE IF NOT EXISTS `plan_course` (
  `id`               int          NOT NULL AUTO_INCREMENT,
  `plan_id`          int          NOT NULL                COMMENT '关联 training_plan.id',
  `course_code`      varchar(32)  NOT NULL                COMMENT '课程代码（关联 course.course_code，非 course_id）',
  `course_name`      varchar(255) NOT NULL                COMMENT '课程名快照',
  `category`         varchar(16)  NOT NULL                COMMENT 'REQUIRED 必修 / ELECTIVE 选修',
  `suggest_semester` int          NULL DEFAULT NULL       COMMENT '建议修读学期序号 1~8（大一上=1）',
  `credit`           decimal(4,1) NOT NULL DEFAULT 0.0    COMMENT '学分快照（避免课程学分变动让方案静默失效）',
  `remark`           varchar(255) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_plan_course`(`plan_id` ASC, `course_code` ASC) USING BTREE,
  INDEX `idx_plan_course_plan`(`plan_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '培养计划课程明细';

-- 绩点规则（校规，可配置；同时只允许一条启用）
CREATE TABLE IF NOT EXISTS `gpa_rule` (
  `id`            int          NOT NULL AUTO_INCREMENT,
  `rule_name`     varchar(64)  NOT NULL,
  `pass_score`    decimal(6,3) NOT NULL DEFAULT 60.000  COMMENT '及格线',
  `rule_type`     varchar(16)  NOT NULL DEFAULT 'FORMULA' COMMENT 'FORMULA 公式 / TABLE 分段表（本轮只实现 FORMULA）',
  `formula`       varchar(255) NULL DEFAULT NULL        COMMENT '公式说明',
  `status`        tinyint      NOT NULL DEFAULT 1       COMMENT '1=启用（同时只允许一条）',
  `create_time`   datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   datetime     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '绩点换算规则';

-- ---------- 5. 回填现有课程的课程代码 ----------
-- 依据课程名逐条映射（已核对 edujwxt.sql 中的真实课程名）
-- 同一门课的所有开课行应共用同一 code，故此处用 course_name 匹配
UPDATE course SET course_code = 'CS101' WHERE course_name = 'Java程序设计'   AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'CS102' WHERE course_name = 'SpringBoot开发' AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'CS103' WHERE course_name = '数据结构与算法' AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'CS104' WHERE course_name = '数据库原理'     AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'EN101' WHERE course_name = '高级英语'       AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'EC101' WHERE course_name = '微观经济学'     AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'MA101' WHERE course_name = '数学分析'       AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'CS105' WHERE course_name = 'Python编程'     AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'CS106' WHERE course_name = '计算机网络'     AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'CS107' WHERE course_name = '操作系统'       AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'EN102' WHERE course_name = '英语口语'       AND (course_code IS NULL OR course_code = '');
UPDATE course SET course_code = 'MG101' WHERE course_name = '管理学原理'     AND (course_code IS NULL OR course_code = '');

-- ---------- 6. 种子数据 ----------

-- 6.1 绩点规则：绩点 = 分数/10 - 5（分数 >= 60），否则 0
INSERT INTO gpa_rule (rule_name, pass_score, rule_type, formula, status)
SELECT '默认规则（分数/10-5）', 60.000, 'FORMULA', '若 分数 < 60 则 0；否则 分数/10 - 5。例：60→1.0、75.76→2.576、87.365→3.7365、100→5.0', 1
WHERE NOT EXISTS (SELECT 1 FROM gpa_rule);

-- 6.2 示例培养计划（⚠️ 仅用于演示与测试；真实计划应由管理员维护）
--      计算机科学与技术（major_id=1）2023 级
--      设计意图：2023001 已通过 CS101/CS102/CS103/CS104 共 14.5 学分（必修全部达成），
--                另需选修学分 → 审核结果可展示「必修已达成 + 选修有缺口」
INSERT INTO training_plan (plan_name, major_id, grade, total_credits, required_credits, elective_credits, status, remark)
SELECT '计算机科学与技术 2023 级培养计划（示例）', 1, '2023', 40.0, 14.5, 25.5, 1,
       '示例数据：现有课程库只有 2 个学期 12 门课，非真实教学计划，仅供演示与测试'
WHERE NOT EXISTS (SELECT 1 FROM training_plan WHERE major_id = 1 AND grade = '2023');

SET @plan2023 := (SELECT id FROM training_plan WHERE major_id = 1 AND grade = '2023' LIMIT 1);

-- 必修：学生 2023001 已通过的 4 门
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'CS101', 'Java程序设计',   'REQUIRED', 1, 4.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'CS101');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'CS102', 'SpringBoot开发', 'REQUIRED', 2, 3.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'CS102');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'CS103', '数据结构与算法', 'REQUIRED', 2, 4.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'CS103');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'CS104', '数据库原理',     'REQUIRED', 3, 3.5
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'CS104');

-- 选修池：其余课程（供「推荐选修」使用）
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'CS105', 'Python编程',     'ELECTIVE', 3, 3.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'CS105');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'CS106', '计算机网络',     'ELECTIVE', 4, 3.5
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'CS106');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'CS107', '操作系统',       'ELECTIVE', 4, 4.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'CS107');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'MA101', '数学分析',       'ELECTIVE', 1, 5.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'MA101');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'EN101', '高级英语',       'ELECTIVE', 2, 2.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'EN101');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'EN102', '英语口语',       'ELECTIVE', 3, 2.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'EN102');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'EC101', '微观经济学',     'ELECTIVE', 3, 3.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'EC101');
INSERT INTO plan_course (plan_id, course_code, course_name, category, suggest_semester, credit)
SELECT @plan2023, 'MG101', '管理学原理',     'ELECTIVE', 4, 3.0
WHERE NOT EXISTS (SELECT 1 FROM plan_course WHERE plan_id = @plan2023 AND course_code = 'MG101');

-- ---------- 完成 ----------
