-- =============================================================
-- 演示考试日期"重新锚定"脚本（2026-09-27 新增，可重复执行）
--
-- 背景（一次真实的夹具老化事故）：
--   seed_data.sql 里的考试时间是**相对 CURDATE()** 计算的（+7/+8/+9 天，补考 -20 天），
--   但**导入那一刻**就被固化成了绝对时间。于是库里那 3 场"未开考"的期末，
--   随着时钟走到考试日之后，就自动变成了"已考过"——
--   症状是 ExamServiceTest.upcomingFilterExcludesPastExams 期望 3 场、实际 2 场，
--   以及 .dsh/verify-p4-api.ps1 / verify-p4-ui.cjs 里同样硬编码的"待考=3"开始失败。
--
-- 这类"夹具随时间老化"没法靠改断言根治（改完过几天又过期），
-- 所以提供这个脚本：**在跑考试相关测试/演示之前执行一次**，把演示数据重新对齐到今天。
-- 执行后：期末 CS101/CS102/CS103 分别落在 +7/+8/+9 天，补考 CS104 落在 -20 天（保持"已考过"的夹具语义）。
--
-- 用法：
--   mysql -uroot -p edujwxt < docs/sql/2026-09-27-reanchor-demo-exam-dates.sql
-- =============================================================

USE edujwxt;

UPDATE `exam_schedule` es
JOIN `course` c ON es.`course_id` = c.`id`
SET es.`exam_time` = DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 9 HOUR)
WHERE c.`course_code` = 'CS101' AND es.`exam_type` = 'FINAL';

UPDATE `exam_schedule` es
JOIN `course` c ON es.`course_id` = c.`id`
SET es.`exam_time` = DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 14 HOUR)
WHERE c.`course_code` = 'CS102' AND es.`exam_type` = 'FINAL';

UPDATE `exam_schedule` es
JOIN `course` c ON es.`course_id` = c.`id`
SET es.`exam_time` = DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 9 HOUR)
WHERE c.`course_code` = 'CS103' AND es.`exam_type` = 'FINAL';

UPDATE `exam_schedule` es
JOIN `course` c ON es.`course_id` = c.`id`
SET es.`exam_time` = DATE_ADD(DATE_SUB(CURDATE(), INTERVAL 20 DAY), INTERVAL 14 HOUR)
WHERE c.`course_code` = 'CS104' AND es.`exam_type` = 'MAKEUP';

SELECT c.`course_code`, es.`exam_type`, es.`exam_time`,
       IF(es.`exam_time` > NOW(), '未开考', '已考过') AS `phase`
FROM `exam_schedule` es JOIN `course` c ON es.`course_id` = c.`id`
ORDER BY es.`exam_time`;
