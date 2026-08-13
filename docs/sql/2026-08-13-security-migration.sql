-- =============================================================
-- 2026-08-13 安全加固迁移脚本（在 edujwxt 库执行一次）
-- 内容：清理重复数据 + 添加业务唯一约束 + 高频查询索引
-- 注意：本脚本只需执行一次；重复执行 ALTER 会报错（属正常，说明已应用）
-- =============================================================

USE edujwxt;

-- ---------- 1. 清理重复数据（保留每组最小 id 的记录） ----------

DELETE s1 FROM course_selection s1
JOIN course_selection s2
  ON s1.course_id = s2.course_id AND s1.student_id = s2.student_id AND s1.id > s2.id;

DELETE s1 FROM score s1
JOIN score s2
  ON s1.course_id = s2.course_id AND s1.student_id = s2.student_id AND s1.id > s2.id;

DELETE s1 FROM teacher_evaluation s1
JOIN teacher_evaluation s2
  ON s1.course_id = s2.course_id AND s1.student_id = s2.student_id AND s1.id > s2.id;

-- ---------- 2. 业务唯一约束（并发/重复操作的最后防线） ----------

-- 防重复选课（配合后端行锁，双保险）
ALTER TABLE course_selection
  ADD CONSTRAINT uk_course_selection_course_student UNIQUE (course_id, student_id);

-- 防同一学生同一课程多条成绩
ALTER TABLE score
  ADD CONSTRAINT uk_score_course_student UNIQUE (course_id, student_id);

-- 防同一学生同一课程重复评价
ALTER TABLE teacher_evaluation
  ADD CONSTRAINT uk_eval_course_student UNIQUE (course_id, student_id);

-- ---------- 3. 高频查询索引 ----------

-- 选课：按学生查已选列表
CREATE INDEX idx_course_selection_student ON course_selection(student_id);
-- 成绩：按学生查成绩（/score/my）
CREATE INDEX idx_score_student ON score(student_id);
-- 课程：按教师查课程（/course/my、教师归属校验）
CREATE INDEX idx_course_teacher ON course(teacher_id);
-- 班级：按专业查班级（学生表单专业联动）
CREATE INDEX idx_clazz_major ON clazz(major_id);
-- 专业：按学院查专业（专业表单学院联动）
CREATE INDEX idx_major_college ON major(college_id);

-- 完成
