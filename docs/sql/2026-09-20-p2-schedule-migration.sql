-- =============================================================
-- 2026-09-20 P2 迁移：排课 + 教室 + 冲突检测
--
-- 依据文档：docs/教务业务扩展设计.md（§2.4 时间冲突、§3.2 新增表 (4)~(6)(10)、§5 P2）
--
-- 本脚本内容：
--   1. room            教室（8 栋 × 10 层 × 10 间 = 800 间，**程序化生成**）
--   2. class_time      上课时间安排（冲突检测的数据基础）
--   3. course_apply    教师开课申请（审批流）
--   4. class_time_apply 教师排课申请（与开课申请分开的第二个审批流）
--   5. 示例课表（3 条，供冲突检测与"我的课表"演示/测试）
--
-- 配套的代码侧修复（P1 遗留，非 SQL）：course.course_code 这一列 P1 已建好，
-- 但 Course POJO 与 CourseMapper 一直没带上它，导致管理员新建的课程 course_code 永远为 NULL。
-- P2 的"审批通过生成 course"依赖该字段，故在 Course.java / CourseMapper 补齐（见开发记录）。
--
-- ⚠️ 可重复执行：建表用 IF NOT EXISTS；教室用 LEFT JOIN 反连接补齐缺失行；
--    示例课表逐条判重
--
-- ⚠️ **冲突判据不能靠数据库唯一约束**（区间重叠不是等值），必须在 Service 层查询判断。
--    本脚本只负责存数据与加索引。
--
-- ⚠️ class_time **刻意不存 term**：学期由 course.term 联表得到。
--    冗余一份 term 会在 course.term 变更后静默失真，故宁可多一次 join。
-- =============================================================

USE edujwxt;

-- ---------- 1. room 教室 ----------
CREATE TABLE IF NOT EXISTS `room` (
  `id`         int         NOT NULL AUTO_INCREMENT,
  `building`   varchar(32) NOT NULL                COMMENT '楼栋，如 教1',
  `floor_no`   int         NOT NULL                COMMENT '楼层 1~10',
  `room_no`    varchar(32) NOT NULL                COMMENT '房间号 01~10',
  `room_name`  varchar(64) NOT NULL                COMMENT '展示名，如 教1-301',
  `capacity`   int         NOT NULL DEFAULT 60     COMMENT '容量（大教室，种子取 60~240）',
  `room_type`  varchar(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/LAB/MULTIMEDIA【假设】',
  `status`     tinyint     NOT NULL DEFAULT 1      COMMENT '1=可用 0=停用',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_room`(`building` ASC, `floor_no` ASC, `room_no` ASC) USING BTREE,
  INDEX `idx_room_pick`(`status` ASC, `capacity` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教室';

-- ---------- 2. class_time 上课时间安排 ----------
CREATE TABLE IF NOT EXISTS `class_time` (
  `id`           int     NOT NULL AUTO_INCREMENT,
  `course_id`    int     NOT NULL              COMMENT '关联 course.id（一次开课）',
  `weekday`      tinyint NOT NULL              COMMENT '1~7（周一~周日）',
  `start_period` tinyint NOT NULL              COMMENT '起始节次 1~10',
  `end_period`   tinyint NOT NULL              COMMENT '结束节次 1~10（含）',
  `start_week`   tinyint NOT NULL              COMMENT '起始周，如 1 或 9',
  `end_week`     tinyint NOT NULL              COMMENT '结束周，如 16',
  `room_id`      int     NULL DEFAULT NULL     COMMENT '关联 room.id，可空（尚未分配）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_class_time_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_class_time_room`(`room_id` ASC) USING BTREE,
  -- 冲突查询的形状：按星期取候选，再比较节次/周次区间
  INDEX `idx_class_time_slot`(`weekday` ASC, `start_period` ASC, `end_period` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '上课时间安排（冲突检测的数据基础）';

-- ---------- 3. course_apply 教师开课申请 ----------
CREATE TABLE IF NOT EXISTS `course_apply` (
  `id`                    int          NOT NULL AUTO_INCREMENT,
  `teacher_id`            varchar(32)  NOT NULL           COMMENT '申请人（教师工号）',
  `course_code`           varchar(32)  NOT NULL           COMMENT '拟开课程代码',
  `course_name`           varchar(255) NOT NULL,
  `term`                  varchar(64)  NOT NULL           COMMENT '拟开学期',
  `college_id`            int          NULL DEFAULT NULL  COMMENT '开课学院（默认取教师所属学院）',
  `credit`                decimal(4,1) NOT NULL DEFAULT 0.0,
  `class_hour`            int          NOT NULL DEFAULT 0,
  `max_student`           int          NOT NULL DEFAULT 0 COMMENT '选课容量 = 教学班容量（用户确认二者一致）',
  `expected_weekday`      tinyint      NULL DEFAULT NULL  COMMENT '教师期望星期 1~7',
  `expected_start_period` tinyint      NULL DEFAULT NULL,
  `expected_end_period`   tinyint      NULL DEFAULT NULL,
  `expected_start_week`   tinyint      NULL DEFAULT NULL,
  `expected_end_week`     tinyint      NULL DEFAULT NULL,
  `prefer_room_id`        int          NULL DEFAULT NULL  COMMENT '期望教室，可空（由系统分配）',
  `status`                varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `reject_reason`         varchar(255) NULL DEFAULT NULL,
  `reviewer`              varchar(32)  NULL DEFAULT NULL,
  `review_time`           datetime     NULL DEFAULT NULL,
  `created_course_id`     int          NULL DEFAULT NULL  COMMENT '审批通过后生成/关联的 course.id',
  `create_time`           datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_apply_status`(`status` ASC) USING BTREE,
  INDEX `idx_apply_teacher`(`teacher_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_apply_term`(`term` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教师开课申请（审批流）';

-- ---------- 4. class_time_apply 教师排课申请 ----------
CREATE TABLE IF NOT EXISTS `class_time_apply` (
  `id`            int          NOT NULL AUTO_INCREMENT,
  `course_id`     int          NOT NULL           COMMENT '关联已审批的 course.id',
  `teacher_id`    varchar(32)  NOT NULL,
  `weekday`       tinyint      NOT NULL,
  `start_period`  tinyint      NOT NULL,
  `end_period`    tinyint      NOT NULL,
  `start_week`    tinyint      NOT NULL,
  `end_week`      tinyint      NOT NULL,
  `room_id`       int          NULL DEFAULT NULL  COMMENT '期望教室，可空（系统推荐）',
  `status`        varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  `conflict_info` varchar(500) NULL DEFAULT NULL  COMMENT '冲突详情（校验失败时记录，最长 500）',
  `reject_reason` varchar(255) NULL DEFAULT NULL,
  `reviewer`      varchar(32)  NULL DEFAULT NULL,
  `review_time`   datetime     NULL DEFAULT NULL,
  `create_time`   datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_ctapply_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_ctapply_status`(`status` ASC) USING BTREE,
  INDEX `idx_ctapply_teacher`(`teacher_id` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教师排课申请（与开课申请分开的第二个审批流）';

-- ---------- 5. 程序化生成 800 间教室 ----------
-- 8 栋 × 10 层 × 10 间。用手写 SQL 铺 800 行既冗长又容易错，故用「数字表 × 数字表」交叉连接生成。
-- 容量取 40 + 楼层*20 = 60~240，便于「按容量最接近」推荐时有区分度。
DROP TEMPORARY TABLE IF EXISTS `tmp_room_seed`;
CREATE TEMPORARY TABLE `tmp_room_seed` (
  `building`  varchar(32),
  `floor_no`  int,
  `room_no`   varchar(32),
  `room_name` varchar(64),
  `capacity`  int,
  `room_type` varchar(16)
);

INSERT INTO `tmp_room_seed` (building, floor_no, room_no, room_name, capacity, room_type)
SELECT CONCAT('教', b.n),
       f.n,
       LPAD(r.n, 2, '0'),
       CONCAT('教', b.n, '-', f.n, LPAD(r.n, 2, '0')),
       40 + f.n * 20,
       CASE WHEN r.n = 10 THEN 'LAB' WHEN f.n = 10 THEN 'MULTIMEDIA' ELSE 'NORMAL' END
FROM (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8) b
CROSS JOIN (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
            UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10) f
CROSS JOIN (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
            UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10) r;

-- 反连接补齐缺失行：可重复执行，且只补缺口（不会重复插入）
INSERT INTO `room` (building, floor_no, room_no, room_name, capacity, room_type, status)
SELECT s.building, s.floor_no, s.room_no, s.room_name, s.capacity, s.room_type, 1
FROM `tmp_room_seed` s
LEFT JOIN `room` r
       ON r.building = s.building AND r.floor_no = s.floor_no AND r.room_no = s.room_no
WHERE r.id IS NULL;

DROP TEMPORARY TABLE IF EXISTS `tmp_room_seed`;

-- ---------- 6. 示例课表（供冲突检测与「我的课表」演示/测试） ----------
-- 刻意覆盖三种情况：
--   CS101  周一 1-2 节、第 1-16 周、教1-101  —— 整学期；教师 10001；做「教师冲突」与「教室冲突」的被测对象
--   CS102  周三 3-4 节、第 1-16 周、教1-102  —— 整学期；与 CS101 星期不同，不冲突
--   CS105  周二 3-5 节、第 9-16 周、教1-201  —— **非整学期**（用户明确存在第 9 周开始的课）+ 3 课时块
SET @room101 := (SELECT id FROM room WHERE room_name = '教1-101' LIMIT 1);
SET @room102 := (SELECT id FROM room WHERE room_name = '教1-102' LIMIT 1);
SET @room201 := (SELECT id FROM room WHERE room_name = '教1-201' LIMIT 1);
SET @cs101 := (SELECT id FROM course WHERE course_code = 'CS101' ORDER BY id LIMIT 1);
SET @cs102 := (SELECT id FROM course WHERE course_code = 'CS102' ORDER BY id LIMIT 1);
SET @cs105 := (SELECT id FROM course WHERE course_code = 'CS105' ORDER BY id LIMIT 1);

INSERT INTO `class_time` (course_id, weekday, start_period, end_period, start_week, end_week, room_id)
SELECT @cs101, 1, 1, 2, 1, 16, @room101
WHERE @cs101 IS NOT NULL AND @room101 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM class_time WHERE course_id = @cs101 AND weekday = 1 AND start_period = 1);

INSERT INTO `class_time` (course_id, weekday, start_period, end_period, start_week, end_week, room_id)
SELECT @cs102, 3, 3, 4, 1, 16, @room102
WHERE @cs102 IS NOT NULL AND @room102 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM class_time WHERE course_id = @cs102 AND weekday = 3 AND start_period = 3);

INSERT INTO `class_time` (course_id, weekday, start_period, end_period, start_week, end_week, room_id)
SELECT @cs105, 2, 3, 5, 9, 16, @room201
WHERE @cs105 IS NOT NULL AND @room201 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM class_time WHERE course_id = @cs105 AND weekday = 2 AND start_period = 3);

-- ---------- 完成 ----------
SELECT (SELECT COUNT(*) FROM room) AS room_rows,
       (SELECT COUNT(*) FROM class_time) AS class_time_rows;
