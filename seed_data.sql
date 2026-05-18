-- ========================================
-- 教务系统测试数据填充脚本
-- ========================================
-- 注意: 所有密码使用已有 BCrypt 哈希（统一密码）
-- ========================================

-- ----------------------------
-- 1. 学院 (college)
-- ----------------------------
INSERT INTO `college` VALUES (4, '理学院');
INSERT INTO `college` VALUES (5, '马克思主义学院');

-- ----------------------------
-- 2. 专业 (major)
-- ----------------------------
INSERT INTO `major` VALUES (5, '数学与应用数学', 4);
INSERT INTO `major` VALUES (6, '信息与计算科学', 4);
INSERT INTO `major` VALUES (7, '思想政治教育', 5);
INSERT INTO `major` VALUES (8, '数据科学与大数据技术', 1);

-- ----------------------------
-- 3. 班级 (clazz)
-- ----------------------------
INSERT INTO `clazz` VALUES (5, '2023级数学1班', 5, '2023');
INSERT INTO `clazz` VALUES (6, '2023级大数据1班', 8, '2023');
INSERT INTO `clazz` VALUES (7, '2024级计算机1班', 1, '2024');
INSERT INTO `clazz` VALUES (8, '2024级软件1班', 2, '2024');
INSERT INTO `clazz` VALUES (9, '2024级英语1班', 3, '2024');
INSERT INTO `clazz` VALUES (10, '2024级会计1班', 4, '2024');

-- ----------------------------
-- 4. 系统用户 (sys_user)
-- ----------------------------
INSERT INTO `sys_user` VALUES (6, '10003', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '10003@edu.com', '13912340001', 'teacher', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (7, '10004', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '10004@edu.com', '13912340002', 'teacher', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (8, '10005', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '10005@edu.com', '13912340003', 'teacher', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (9, '10006', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '10006@edu.com', '13912340004', 'teacher', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (10, '2023003', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '003@163.com', '13345670003', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (11, '2023004', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '004@163.com', '13345670004', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (12, '2023005', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '005@163.com', '13345670005', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (13, '2023006', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '006@163.com', '13345670006', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (14, '2023007', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '007@163.com', '13345670007', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (15, '2023008', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '008@163.com', '13345670008', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (16, '2024001', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '009@163.com', '13345670009', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (17, '2024002', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '010@163.com', '13345670010', 'student', 1, NOW(), NOW());
INSERT INTO `sys_user` VALUES (18, 'admin02', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', 'admin02@edu.com', '13618669988', 'admin', 1, NOW(), NOW());

-- ----------------------------
-- 5. 教师 (teacher)
-- ----------------------------
INSERT INTO `teacher` VALUES (3, '张伟', '10003', 6, '男', '1975-09-15', '13912340001', '10003@edu.com', 1, '教授', NOW(), NOW());
INSERT INTO `teacher` VALUES (4, '陈静', '10004', 7, '女', '1985-03-22', '13912340002', '10004@edu.com', 2, '副教授', NOW(), NOW());
INSERT INTO `teacher` VALUES (5, '刘强', '10005', 8, '男', '1992-11-08', '13912340003', '10005@edu.com', 3, '讲师', NOW(), NOW());
INSERT INTO `teacher` VALUES (6, '赵敏', '10006', 9, '女', '1982-06-30', '13912340004', '10006@edu.com', 4, '教授', NOW(), NOW());

-- ----------------------------
-- 6. 学生 (student)
-- ----------------------------
INSERT INTO `student` VALUES (3, '王五', '2023003', 10, 1, '男', '2005-03-10', '13345670003', '003@163.com', NOW(), NOW());
INSERT INTO `student` VALUES (4, '赵六', '2023004', 11, 2, '女', '2005-07-18', '13345670004', '004@163.com', NOW(), NOW());
INSERT INTO `student` VALUES (5, '孙七', '2023005', 12, 3, '男', '2004-12-01', '13345670005', '005@163.com', NOW(), NOW());
INSERT INTO `student` VALUES (6, '周八', '2023006', 13, 4, '女', '2005-09-25', '13345670006', '006@163.com', NOW(), NOW());
INSERT INTO `student` VALUES (7, '吴九', '2023007', 14, 5, '男', '2005-05-12', '13345670007', '007@163.com', NOW(), NOW());
INSERT INTO `student` VALUES (8, '郑十', '2023008', 15, 6, '女', '2005-01-30', '13345670008', '008@163.com', NOW(), NOW());
INSERT INTO `student` VALUES (9, '钱一一', '2024001', 16, 7, '男', '2006-04-15', '13345670009', '009@163.com', NOW(), NOW());
INSERT INTO `student` VALUES (10, '冯二二', '2024002', 17, 8, '女', '2006-08-20', '13345670010', '010@163.com', NOW(), NOW());

-- ----------------------------
-- 7. 课程 (course)
-- ----------------------------
INSERT INTO `course` VALUES (3, '数据结构与算法', '10003', 1, '2024-2025-1', 4.0, 64, 50);
INSERT INTO `course` VALUES (4, '数据库原理', '10001', 1, '2024-2025-1', 3.5, 56, 45);
INSERT INTO `course` VALUES (5, '高级英语', '10004', 2, '2024-2025-1', 2.0, 32, 40);
INSERT INTO `course` VALUES (6, '微观经济学', '10005', 3, '2024-2025-1', 3.0, 48, 50);
INSERT INTO `course` VALUES (7, '数学分析', '10006', 4, '2024-2025-1', 5.0, 80, 40);
INSERT INTO `course` VALUES (8, 'Python编程', '10003', 1, '2024-2025-2', 3.0, 48, 50);
INSERT INTO `course` VALUES (9, '计算机网络', '10002', 1, '2024-2025-2', 3.5, 56, 45);
INSERT INTO `course` VALUES (10, '操作系统', '10001', 1, '2024-2025-2', 4.0, 64, 50);
INSERT INTO `course` VALUES (11, '英语口语', '10004', 2, '2024-2025-2', 2.0, 32, 35);
INSERT INTO `course` VALUES (12, '管理学原理', '10005', 3, '2024-2025-2', 3.0, 48, 50);

-- ----------------------------
-- 8. 选课 (course_selection)
-- ----------------------------
-- 张三(2023001): 已选 Java(1), SpringBoot(2), 新增 数据结构(3), 数据库原理(4)
INSERT INTO `course_selection` VALUES (4, 3, '2023001', NOW());
INSERT INTO `course_selection` VALUES (5, 4, '2023001', NOW());
-- 李四(2023002): 已选 Java(1), 新增 SpringBoot(2), 高级英语(5), Python编程(8)
INSERT INTO `course_selection` VALUES (6, 2, '2023002', NOW());
INSERT INTO `course_selection` VALUES (7, 5, '2023002', NOW());
INSERT INTO `course_selection` VALUES (8, 8, '2023002', NOW());
-- 王五(2023003): Java(1), 数据结构(3), 数据库原理(4), Python编程(8)
INSERT INTO `course_selection` VALUES (9, 1, '2023003', NOW());
INSERT INTO `course_selection` VALUES (10, 3, '2023003', NOW());
INSERT INTO `course_selection` VALUES (11, 4, '2023003', NOW());
INSERT INTO `course_selection` VALUES (12, 8, '2023003', NOW());
-- 赵六(2023004): SpringBoot(2), 计算机网络(9), 操作系统(10)
INSERT INTO `course_selection` VALUES (13, 2, '2023004', NOW());
INSERT INTO `course_selection` VALUES (14, 9, '2023004', NOW());
INSERT INTO `course_selection` VALUES (15, 10, '2023004', NOW());
-- 孙七(2023005): 高级英语(5), 英语口语(11)
INSERT INTO `course_selection` VALUES (16, 5, '2023005', NOW());
INSERT INTO `course_selection` VALUES (17, 11, '2023005', NOW());
-- 周八(2023006): 微观经济学(6), 管理学原理(12)
INSERT INTO `course_selection` VALUES (18, 6, '2023006', NOW());
INSERT INTO `course_selection` VALUES (19, 12, '2023006', NOW());
-- 吴九(2023007): 数学分析(7), Java(1)
INSERT INTO `course_selection` VALUES (20, 7, '2023007', NOW());
INSERT INTO `course_selection` VALUES (21, 1, '2023007', NOW());
-- 郑十(2023008): 数据结构(3), Python编程(8), 计算机网络(9)
INSERT INTO `course_selection` VALUES (22, 3, '2023008', NOW());
INSERT INTO `course_selection` VALUES (23, 8, '2023008', NOW());
INSERT INTO `course_selection` VALUES (24, 9, '2023008', NOW());
-- 钱一一(2024001): Java(1), 数据结构(3), Python编程(8)
INSERT INTO `course_selection` VALUES (25, 1, '2024001', NOW());
INSERT INTO `course_selection` VALUES (26, 3, '2024001', NOW());
INSERT INTO `course_selection` VALUES (27, 8, '2024001', NOW());
-- 冯二二(2024002): SpringBoot(2), 计算机网络(9)
INSERT INTO `course_selection` VALUES (28, 2, '2024002', NOW());
INSERT INTO `course_selection` VALUES (29, 9, '2024002', NOW());

-- ----------------------------
-- 9. 成绩 (score)
-- ----------------------------
-- 张三 - 数据结构
INSERT INTO `score` VALUES (4, 3, '2023001', 88.0, 92.0, 90.4);
-- 张三 - 数据库原理
INSERT INTO `score` VALUES (5, 4, '2023001', 90.0, 85.0, 87.0);
-- 李四 - 高级英语
INSERT INTO `score` VALUES (6, 5, '2023002', 85.0, 88.0, 86.8);
-- 李四 - Python编程
INSERT INTO `score` VALUES (7, 8, '2023002', 80.0, 75.0, 77.0);
-- 王五 - Java程序设计
INSERT INTO `score` VALUES (8, 1, '2023003', 70.0, 78.0, 74.8);
-- 王五 - 数据结构
INSERT INTO `score` VALUES (9, 3, '2023003', 82.0, 86.0, 84.4);
-- 王五 - 数据库原理
INSERT INTO `score` VALUES (10, 4, '2023003', 75.0, 80.0, 78.0);
-- 赵六 - SpringBoot
INSERT INTO `score` VALUES (11, 2, '2023004', 95.0, 90.0, 92.0);
-- 孙七 - 高级英语
INSERT INTO `score` VALUES (12, 5, '2023005', 88.0, 92.0, 90.4);
-- 周八 - 微观经济学
INSERT INTO `score` VALUES (13, 6, '2023006', 78.0, 82.0, 80.4);
-- 吴九 - 数学分析
INSERT INTO `score` VALUES (14, 7, '2023007', 65.0, 70.0, 68.0);
-- 郑十 - 数据结构
INSERT INTO `score` VALUES (15, 3, '2023008', 91.0, 95.0, 93.4);
-- 钱一一 - Java程序设计
INSERT INTO `score` VALUES (16, 1, '2024001', 60.0, 72.0, 67.2);
-- 冯二二 - SpringBoot
INSERT INTO `score` VALUES (17, 2, '2024002', 85.0, 88.0, 86.8);

-- ----------------------------
-- 10. 重置自增主键
-- ----------------------------
-- (不要重置 college, major, clazz, course, course_selection, score, student, teacher 的自增值，
--  因为后续可能还需要手动插入更多数据，保留当前最大值即可)
