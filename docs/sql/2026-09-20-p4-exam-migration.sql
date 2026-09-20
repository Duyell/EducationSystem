-- =============================================================
-- 2026-09-20 P4 迁移：考试安排
--
-- 依据文档：docs/教务业务扩展设计.md（§3.2(9) 表设计、§5 P4、§6.5 验收清单）
--
-- 本脚本内容：
--   1. exam_schedule 考试安排表
--   2. 种子：4 场考试（3 场未来 + 1 场已过，用于验证"按时间排序/只看未来"）
--
-- ⚠️ 可重复执行（建表 IF NOT EXISTS；种子判重）
--
-- ⚠️ 时间冲突判据与 P2 的**节次**冲突不同，务必别混：
--   · 节次是**离散格子**（第 3-4 节与第 4-5 节共用第 4 节）→ 用闭区间 `<=` / `>=`
--   · 考试是**连续时钟区间**（10:00-12:00 与 12:00-14:00 只是首尾相接）→ 用半开区间
--     `existing.start < newEnd AND existing.end > newStart`，**恰好相接不算冲突**
--   两处判据在 SQL 里各自成文，不要互相"统一"，它们的语义本来就不同。
-- =============================================================

USE edujwxt;

-- ---------- 1. exam_schedule 考试安排 ----------
CREATE TABLE IF NOT EXISTS `exam_schedule` (
  `id`               int          NOT NULL AUTO_INCREMENT,
  `course_id`        int          NOT NULL              COMMENT '关联 course.id',
  `exam_type`        varchar(16)  NOT NULL DEFAULT 'FINAL' COMMENT 'FINAL 期末 / MAKEUP 补考 / MIDTERM 期中',
  `exam_time`        datetime     NOT NULL              COMMENT '考试开始时间',
  `duration_minutes` int          NOT NULL DEFAULT 120  COMMENT '考试时长（分钟）',
  `room_id`          int          NULL DEFAULT NULL     COMMENT '考场（关联 room.id），可空=待定',
  `seat_range`       varchar(64)  NULL DEFAULT NULL     COMMENT '座位/考场号段，如 A区01-30',
  `invigilator`      varchar(255) NULL DEFAULT NULL     COMMENT '监考教师（可多人，逗号分隔）',
  `status`           tinyint      NOT NULL DEFAULT 1    COMMENT '1=有效 0=作废',
  `remark`           varchar(255) NULL DEFAULT NULL,
  `create_time`      datetime     NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      datetime     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_exam_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_exam_time`(`exam_time` ASC) USING BTREE,
  -- 比设计文档多这一条索引：考场冲突查询按 room_id 过滤，不建索引会全表扫
  INDEX `idx_exam_room`(`room_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '考试安排';

-- ---------- 2. 种子考试 ----------
-- 时间用「今天 + N 天 + 固定时刻」而不是写死日期：写死的话过一阵子学生就永远看不到
-- "我下周有什么考试"，演示数据等于废了。与 seed_data.sql 的种子保持一致。
-- 覆盖三种情况：未来 3 场（期末）+ 过去 1 场（补考），用于验证排序与「只看未来」。

SET @cs101 := (SELECT id FROM course WHERE course_code = 'CS101' ORDER BY id LIMIT 1);
SET @cs102 := (SELECT id FROM course WHERE course_code = 'CS102' ORDER BY id LIMIT 1);
SET @cs103 := (SELECT id FROM course WHERE course_code = 'CS103' ORDER BY id LIMIT 1);
SET @cs104 := (SELECT id FROM course WHERE course_code = 'CS104' ORDER BY id LIMIT 1);
SET @room101 := (SELECT id FROM room WHERE room_name = '教1-101' LIMIT 1);
SET @room102 := (SELECT id FROM room WHERE room_name = '教1-102' LIMIT 1);
SET @room201 := (SELECT id FROM room WHERE room_name = '教1-201' LIMIT 1);

INSERT INTO `exam_schedule` (course_id, exam_type, exam_time, duration_minutes, room_id, seat_range, invigilator, status)
SELECT @cs101, 'FINAL', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 9 HOUR), 120, @room101, 'A区01-50', '王雨', 1
WHERE @cs101 IS NOT NULL AND @room101 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM exam_schedule WHERE course_id = @cs101 AND exam_type = 'FINAL');

INSERT INTO `exam_schedule` (course_id, exam_type, exam_time, duration_minutes, room_id, seat_range, invigilator, status)
SELECT @cs102, 'FINAL', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 14 HOUR), 120, @room102, 'A区01-50', '李强', 1
WHERE @cs102 IS NOT NULL AND @room102 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM exam_schedule WHERE course_id = @cs102 AND exam_type = 'FINAL');

INSERT INTO `exam_schedule` (course_id, exam_type, exam_time, duration_minutes, room_id, seat_range, invigilator, status)
SELECT @cs103, 'FINAL', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 9 HOUR), 120, @room201, 'B区01-60', '王雨,李强', 1
WHERE @cs103 IS NOT NULL AND @room201 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM exam_schedule WHERE course_id = @cs103 AND exam_type = 'FINAL');

-- 已过的那场：补考，用于验证"排序"和"只看未来"确实在起作用
INSERT INTO `exam_schedule` (course_id, exam_type, exam_time, duration_minutes, room_id, seat_range, invigilator, status)
SELECT @cs104, 'MAKEUP', DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 20 DAY), INTERVAL 14 HOUR), 90, @room101, 'A区01-50', '王雨', 1
WHERE @cs104 IS NOT NULL AND @room101 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM exam_schedule WHERE course_id = @cs104 AND exam_type = 'MAKEUP');

-- ---------- 完成 ----------
SELECT es.id, c.course_code, es.exam_type, es.exam_time, es.duration_minutes, r.room_name,
       (es.exam_time >= NOW()) AS is_upcoming
FROM exam_schedule es
JOIN course c ON es.course_id = c.id
LEFT JOIN room r ON es.room_id = r.id
ORDER BY es.exam_time;
