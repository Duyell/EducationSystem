/*
 Navicat Premium Dump SQL

 Source Server         : Mysql8.0
 Source Server Type    : MySQL
 Source Server Version : 80040 (8.0.40)
 Source Host           : localhost:3306
 Source Schema         : edujwxt

 Target Server Type    : MySQL
 Target Server Version : 80040 (8.0.40)
 File Encoding         : 65001

 Date: 07/05/2026 10:23:36
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for clazz
-- ----------------------------
DROP TABLE IF EXISTS `clazz`;
CREATE TABLE `clazz`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `clazz_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `major_id` int NOT NULL,
  `grade` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_clazz_major`(`major_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of clazz
-- ----------------------------
INSERT INTO `clazz` (`id`, `clazz_name`, `major_id`, `grade`) VALUES (1, '2023级计算机1班', 1, '2023');
INSERT INTO `clazz` (`id`, `clazz_name`, `major_id`, `grade`) VALUES (2, '2023级软件1班', 2, '2023');
INSERT INTO `clazz` (`id`, `clazz_name`, `major_id`, `grade`) VALUES (3, '2023级英语1班', 3, '2023');
INSERT INTO `clazz` (`id`, `clazz_name`, `major_id`, `grade`) VALUES (4, '2023级会计1班', 4, '2023');

-- ----------------------------
-- Table structure for college
-- ----------------------------
DROP TABLE IF EXISTS `college`;
CREATE TABLE `college`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `college_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of college
-- ----------------------------
INSERT INTO `college` (`id`, `college_name`) VALUES (1, '计算机学院');
INSERT INTO `college` (`id`, `college_name`) VALUES (2, '外国语学院');
INSERT INTO `college` (`id`, `college_name`) VALUES (3, '经济管理学院');

-- ----------------------------
-- Table structure for course
-- ----------------------------
DROP TABLE IF EXISTS `course`;
CREATE TABLE `course`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `course_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `course_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `teacher_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `college_id` int NOT NULL,
  `term` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `credit` decimal(4, 1) NOT NULL,
  `class_hour` int NOT NULL,
  `max_student` int NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_course_code`(`course_code` ASC) USING BTREE,
  INDEX `idx_course_teacher`(`teacher_id` ASC) USING BTREE,
  INDEX `idx_course_college`(`college_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of course
-- ----------------------------
INSERT INTO `course` (`id`, `course_code`, `course_name`, `teacher_id`, `college_id`, `term`, `credit`, `class_hour`, `max_student`) VALUES (1, 'CS101', 'Java程序设计', '10001', 1, '2024-2025-1', 4.0, 64, 50);
INSERT INTO `course` (`id`, `course_code`, `course_name`, `teacher_id`, `college_id`, `term`, `credit`, `class_hour`, `max_student`) VALUES (2, 'CS102', 'SpringBoot开发', '10002', 1, '2024-2025-1', 3.0, 48, 50);
-- ----------------------------
-- Table structure for course_selection
-- ----------------------------
DROP TABLE IF EXISTS `course_selection`;
CREATE TABLE `course_selection`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `course_id` int NOT NULL,
  `student_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `select_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_course_selection_course_student`(`course_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `idx_course_selection_student`(`student_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of course_selection
-- ----------------------------
INSERT INTO `course_selection` (`id`, `course_id`, `student_id`, `select_time`) VALUES (1, 1, '2023001', '2026-04-09 17:01:32');
INSERT INTO `course_selection` (`id`, `course_id`, `student_id`, `select_time`) VALUES (2, 1, '2023002', '2026-04-09 17:01:32');
INSERT INTO `course_selection` (`id`, `course_id`, `student_id`, `select_time`) VALUES (3, 2, '2023001', '2026-04-09 17:01:32');

-- ----------------------------
-- Table structure for major
-- ----------------------------
DROP TABLE IF EXISTS `major`;
CREATE TABLE `major`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `major_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `college_id` int NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_major_college`(`college_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of major
-- ----------------------------
INSERT INTO `major` (`id`, `major_name`, `college_id`) VALUES (1, '计算机科学与技术', 1);
INSERT INTO `major` (`id`, `major_name`, `college_id`) VALUES (2, '软件工程', 1);
INSERT INTO `major` (`id`, `major_name`, `college_id`) VALUES (3, '英语', 2);
INSERT INTO `major` (`id`, `major_name`, `college_id`) VALUES (4, '会计学', 3);

-- ----------------------------
-- Table structure for score
-- ----------------------------
DROP TABLE IF EXISTS `score`;
CREATE TABLE `score`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `course_id` int NOT NULL,
  `student_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `usual_score` decimal(6, 3) NULL DEFAULT 0.000,
  `exam_score` decimal(6, 3) NULL DEFAULT 0.000,
  `total_score` decimal(6, 3) NULL DEFAULT 0.000,
  `makeup_score` decimal(6, 3) NULL DEFAULT NULL,
  `passed` tinyint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_score_course_student`(`course_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `idx_score_student`(`student_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of score
-- ----------------------------
INSERT INTO `score` (`id`, `course_id`, `student_id`, `usual_score`, `exam_score`, `total_score`, `makeup_score`, `passed`) VALUES (1, 1, '2023001', 85.0, 90.0, 88.0, NULL, 1);
INSERT INTO `score` (`id`, `course_id`, `student_id`, `usual_score`, `exam_score`, `total_score`, `makeup_score`, `passed`) VALUES (2, 1, '2023002', 78.0, 82.0, 80.0, NULL, 1);
INSERT INTO `score` (`id`, `course_id`, `student_id`, `usual_score`, `exam_score`, `total_score`, `makeup_score`, `passed`) VALUES (3, 2, '2023001', 92.0, 88.0, 90.0, NULL, 1);
-- ----------------------------
-- Table structure for student
-- ----------------------------
DROP TABLE IF EXISTS `student`;
CREATE TABLE `student`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `student_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `student_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` int NOT NULL,
  `clazz_id` int NOT NULL,
  `gender` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `birthday` date NULL DEFAULT NULL,
  `phone` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `student_id`(`student_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of student
-- ----------------------------
INSERT INTO `student` (`id`, `student_name`, `student_id`, `user_id`, `clazz_id`, `gender`, `birthday`, `phone`, `email`, `create_time`, `update_time`, `status`) VALUES (1, '张三', '2023001', 4, 1, '男', '2005-01-15', '13345678888', '001@163.com', '2026-04-09 17:01:19', '2026-04-09 17:36:05', 'ACTIVE');
INSERT INTO `student` (`id`, `student_name`, `student_id`, `user_id`, `clazz_id`, `gender`, `birthday`, `phone`, `email`, `create_time`, `update_time`, `status`) VALUES (2, '李四', '2023002', 5, 2, '女', '2004-05-20', '12276549999', '002@163.com', '2026-04-09 17:01:19', '2026-04-09 17:36:21', 'ACTIVE');
-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `username` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `phone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `status` int NULL DEFAULT 1,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 14 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` (`id`, `username`, `password`, `email`, `phone`, `role`, `status`, `create_time`, `update_time`) VALUES (1, 'admin01', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', 'admin@edu.com', '13618669987', 'admin', 1, '2026-04-09 17:00:55', '2026-04-21 15:07:11');
INSERT INTO `sys_user` (`id`, `username`, `password`, `email`, `phone`, `role`, `status`, `create_time`, `update_time`) VALUES (2, '10001', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '10001@edu.com', '18596569999', 'teacher', 1, '2026-04-09 17:00:55', '2026-04-09 19:11:19');
INSERT INTO `sys_user` (`id`, `username`, `password`, `email`, `phone`, `role`, `status`, `create_time`, `update_time`) VALUES (3, '10002', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '10002@edu.com', '17548684864', 'teacher', 1, '2026-04-09 17:00:55', '2026-04-09 19:11:24');
INSERT INTO `sys_user` (`id`, `username`, `password`, `email`, `phone`, `role`, `status`, `create_time`, `update_time`) VALUES (4, '2023001', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '001@163.com', '13345678888', 'student', 1, '2026-04-09 17:00:55', '2026-04-09 19:09:51');
INSERT INTO `sys_user` (`id`, `username`, `password`, `email`, `phone`, `role`, `status`, `create_time`, `update_time`) VALUES (5, '2023002', '$2a$10$Lm2pZVyPC85VLHJ0X/rzyuno7dFPH8MVFLyWqNJ6.PdqBhtCnT8r.', '002@163.com', '12276549999', 'student', 1, '2026-04-09 17:00:55', '2026-04-21 19:48:26');

-- ----------------------------
-- Table structure for teacher
-- ----------------------------
DROP TABLE IF EXISTS `teacher`;
CREATE TABLE `teacher`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `teacher_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `teacher_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` int NOT NULL,
  `gender` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `birthday` date NULL DEFAULT NULL,
  `phone` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `college_id` int NOT NULL,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `teacher_id`(`teacher_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of teacher
-- ----------------------------
INSERT INTO `teacher` (`id`, `teacher_name`, `teacher_id`, `user_id`, `gender`, `birthday`, `phone`, `email`, `college_id`, `title`, `create_time`, `update_time`) VALUES (1, '王雨', '10001', 2, '男', '1980-03-10', '18596569999', '10001@edu.com', 1, '教授', '2026-04-09 17:01:24', '2026-04-22 15:31:40');
INSERT INTO `teacher` (`id`, `teacher_name`, `teacher_id`, `user_id`, `gender`, `birthday`, `phone`, `email`, `college_id`, `title`, `create_time`, `update_time`) VALUES (2, '李明', '10002', 3, '女', '1990-07-25', '17548684864', '10002@edu.com', 1, '讲师', '2026-04-09 17:01:24', '2026-04-09 17:37:16');

-- ----------------------------
-- Table structure for teacher_evaluation
-- ----------------------------
DROP TABLE IF EXISTS `teacher_evaluation`;
CREATE TABLE `teacher_evaluation` (
  `id` int NOT NULL AUTO_INCREMENT,
  `course_id` int NOT NULL,
  `student_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `teacher_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `score` int DEFAULT 5,
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_eval_course_student`(`course_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `idx_eval_teacher`(`teacher_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for ai_tool_audit
-- AI 工具调用审计日志（见 docs/sql/2026-09-19-ai-audit-migration.sql）
-- ----------------------------
DROP TABLE IF EXISTS `ai_tool_audit`;
CREATE TABLE `ai_tool_audit`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `tool_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `risk_level` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `args_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `result_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `error_msg` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `confirm_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `duration_ms` bigint NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_ai_audit_request`(`request_id` ASC) USING BTREE,
  INDEX `idx_ai_audit_user`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_ai_audit_tool`(`tool_name` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_ai_audit_status`(`status` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for training_plan
-- 培养计划（按专业+年级分版）；见 docs/sql/2026-09-20-p1-training-plan-migration.sql
-- ----------------------------
DROP TABLE IF EXISTS `training_plan`;
CREATE TABLE `training_plan`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `plan_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `major_id` int NOT NULL,
  `grade` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `total_credits` decimal(5, 1) NOT NULL DEFAULT 0.0,
  `required_credits` decimal(5, 1) NOT NULL DEFAULT 0.0,
  `elective_credits` decimal(5, 1) NOT NULL DEFAULT 0.0,
  `status` tinyint NOT NULL DEFAULT 1,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_plan_major_grade`(`major_id` ASC, `grade` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for plan_course
-- 培养计划课程明细
-- ----------------------------
DROP TABLE IF EXISTS `plan_course`;
CREATE TABLE `plan_course`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `plan_id` int NOT NULL,
  `course_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `category` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `suggest_semester` int NULL DEFAULT NULL,
  `credit` decimal(4, 1) NOT NULL DEFAULT 0.0,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_plan_course`(`plan_id` ASC, `course_code` ASC) USING BTREE,
  INDEX `idx_plan_course_plan`(`plan_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for gpa_rule
-- 绩点换算规则（校规，可配置）
-- ----------------------------
DROP TABLE IF EXISTS `gpa_rule`;
CREATE TABLE `gpa_rule`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `rule_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `pass_score` decimal(6, 3) NOT NULL DEFAULT 60.000,
  `rule_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'FORMULA',
  `formula` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of gpa_rule
-- 绩点 = 分数/10 - 5（分数 < 60 记 0）；见 docs/教务业务扩展设计.md §2.1
-- ----------------------------
INSERT INTO `gpa_rule` (`rule_name`, `pass_score`, `rule_type`, `formula`, `status`)
VALUES ('默认规则（分数/10-5）', 60.000, 'FORMULA',
        '若 分数 < 60 则 0；否则 分数/10 - 5。例：60→1.0、75.76→2.576、87.365→3.7365、100→5.0', 1);

-- ----------------------------
-- Records of training_plan / plan_course
-- 示例培养计划：计算机科学与技术(major_id=1) 2023 级
-- 与 docs/sql/2026-09-20-p1-training-plan-migration.sql 的种子保持一致。
-- WARNING: 仅用于演示与测试。现有课程库只有 2 个学期 12 门课，不是真实教学计划；
--          真实计划应由管理员在培训计划维护页面上录入。
-- ----------------------------
INSERT INTO `training_plan` (`id`, `plan_name`, `major_id`, `grade`, `total_credits`, `required_credits`, `elective_credits`, `status`, `remark`)
VALUES (1, '计算机科学与技术 2023 级培养计划（示例）', 1, '2023', 40.0, 14.5, 25.5, 1,
        '示例数据：现有课程库只有 2 个学期 12 门课，非真实教学计划，仅供演示与测试');

INSERT INTO `plan_course` (`plan_id`, `course_code`, `course_name`, `category`, `suggest_semester`, `credit`) VALUES
  (1, 'CS101', 'Java程序设计',   'REQUIRED', 1, 4.0),
  (1, 'CS102', 'SpringBoot开发', 'REQUIRED', 2, 3.0),
  (1, 'CS103', '数据结构与算法', 'REQUIRED', 2, 4.0),
  (1, 'CS104', '数据库原理',     'REQUIRED', 3, 3.5),
  (1, 'CS105', 'Python编程',     'ELECTIVE', 3, 3.0),
  (1, 'CS106', '计算机网络',     'ELECTIVE', 4, 3.5),
  (1, 'CS107', '操作系统',       'ELECTIVE', 4, 4.0),
  (1, 'MA101', '数学分析',       'ELECTIVE', 1, 5.0),
  (1, 'EN101', '高级英语',       'ELECTIVE', 2, 2.0),
  (1, 'EN102', '英语口语',       'ELECTIVE', 3, 2.0),
  (1, 'EC101', '微观经济学',     'ELECTIVE', 3, 3.0),
  (1, 'MG101', '管理学原理',     'ELECTIVE', 4, 3.0);

SET FOREIGN_KEY_CHECKS = 1;
