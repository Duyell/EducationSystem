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
  `round_id` int NULL DEFAULT NULL,
  `select_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_course_selection_course_student`(`course_id` ASC, `student_id` ASC) USING BTREE,
  INDEX `idx_course_selection_student`(`student_id` ASC) USING BTREE,
  INDEX `idx_selection_round`(`round_id` ASC) USING BTREE
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

-- ----------------------------
-- Table structure for room
-- 教室：8 栋 × 10 层 × 10 间 = 800 间（用户给出的规模）
-- ----------------------------
DROP TABLE IF EXISTS `room`;
CREATE TABLE `room`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `building` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `floor_no` int NOT NULL,
  `room_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `room_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `capacity` int NOT NULL DEFAULT 60,
  `room_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NORMAL',
  `status` tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_room`(`building` ASC, `floor_no` ASC, `room_no` ASC) USING BTREE,
  INDEX `idx_room_pick`(`status` ASC, `capacity` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for class_time
-- 上课时间安排（冲突检测的数据基础）；刻意不存 term，学期由 course.term 联表得到
-- ----------------------------
DROP TABLE IF EXISTS `class_time`;
CREATE TABLE `class_time`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `course_id` int NOT NULL,
  `weekday` tinyint NOT NULL,
  `start_period` tinyint NOT NULL,
  `end_period` tinyint NOT NULL,
  `start_week` tinyint NOT NULL,
  `end_week` tinyint NOT NULL,
  `room_id` int NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_class_time_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_class_time_room`(`room_id` ASC) USING BTREE,
  INDEX `idx_class_time_slot`(`weekday` ASC, `start_period` ASC, `end_period` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for course_apply
-- 教师开课申请（审批流）：审批通过才生成 course 行
-- ----------------------------
DROP TABLE IF EXISTS `course_apply`;
CREATE TABLE `course_apply`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `teacher_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `course_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `term` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `college_id` int NULL DEFAULT NULL,
  `credit` decimal(4, 1) NOT NULL DEFAULT 0.0,
  `class_hour` int NOT NULL DEFAULT 0,
  `max_student` int NOT NULL DEFAULT 0,
  `expected_weekday` tinyint NULL DEFAULT NULL,
  `expected_start_period` tinyint NULL DEFAULT NULL,
  `expected_end_period` tinyint NULL DEFAULT NULL,
  `expected_start_week` tinyint NULL DEFAULT NULL,
  `expected_end_week` tinyint NULL DEFAULT NULL,
  `prefer_room_id` int NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING',
  `reject_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `reviewer` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `review_time` datetime NULL DEFAULT NULL,
  `created_course_id` int NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_apply_status`(`status` ASC) USING BTREE,
  INDEX `idx_apply_teacher`(`teacher_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_apply_term`(`term` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for class_time_apply
-- 教师排课申请（与开课申请分开的第二个审批流）
-- ----------------------------
DROP TABLE IF EXISTS `class_time_apply`;
CREATE TABLE `class_time_apply`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `course_id` int NOT NULL,
  `teacher_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `weekday` tinyint NOT NULL,
  `start_period` tinyint NOT NULL,
  `end_period` tinyint NOT NULL,
  `start_week` tinyint NOT NULL,
  `end_week` tinyint NOT NULL,
  `room_id` int NULL DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING',
  `conflict_info` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `reject_reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `reviewer` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `review_time` datetime NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_ctapply_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_ctapply_status`(`status` ASC) USING BTREE,
  INDEX `idx_ctapply_teacher`(`teacher_id` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of room
-- 800 行由「数字表 × 数字表 × 数字表」交叉连接程序化生成，不手写 800 条 INSERT。
-- 容量 = 40 + 楼层*20 = 60~240，便于「按容量最接近」推荐时有区分度。
-- 与 docs/sql/2026-09-20-p2-schedule-migration.sql 完全一致。
-- ----------------------------
INSERT INTO `room` (`building`, `floor_no`, `room_no`, `room_name`, `capacity`, `room_type`, `status`)
SELECT CONCAT('教', b.n),
       f.n,
       LPAD(r.n, 2, '0'),
       CONCAT('教', b.n, '-', f.n, LPAD(r.n, 2, '0')),
       40 + f.n * 20,
       CASE WHEN r.n = 10 THEN 'LAB' WHEN f.n = 10 THEN 'MULTIMEDIA' ELSE 'NORMAL' END,
       1
FROM (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8) b
CROSS JOIN (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
            UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10) f
CROSS JOIN (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
            UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10) r;

-- ----------------------------
-- Records of class_time
-- 示例课表**不放在这里**：课表要按 course_code 反查 course.id，而本文件只建了 CS101/CS102
-- 两门课（其余 10 门在 seed_data.sql 里）。放在本文件会因查不到课程而写入 NULL 主键失败。
-- 见 seed_data.sql 第 10 节。
-- ----------------------------

-- ----------------------------
-- Table structure for selection_round
-- 选课轮次（P3）：管理员控制开关 + 选课/补退选时间窗
-- ----------------------------
DROP TABLE IF EXISTS `selection_round`;
CREATE TABLE `selection_round`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `round_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `term` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `select_start` datetime NOT NULL,
  `select_end` datetime NOT NULL,
  `drop_start` datetime NULL DEFAULT NULL,
  `drop_end` datetime NULL DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 0,
  `max_credits` decimal(5, 1) NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_round_term_status`(`term` ASC, `status` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for selection_round_scope
-- 轮次适用范围：grade/major_id/college_id 均可空，NULL=不限
-- ----------------------------
DROP TABLE IF EXISTS `selection_round_scope`;
CREATE TABLE `selection_round_scope`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `round_id` int NOT NULL,
  `grade` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `major_id` int NULL DEFAULT NULL,
  `college_id` int NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_scope_round`(`round_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of selection_round / selection_round_scope
-- 三个轮次分别覆盖三种状态；时间窗用 NOW() 相对计算，写死日期的话过一阵子演示数据就全过期了。
-- 与 docs/sql/2026-09-20-p3-selection-round-migration.sql 一致。
--   ① 2024-2025-1 开启中 → 可选可退
--   ② 2024-2025-2 补退选 → 只能退（选课窗口已过、退课窗口开放）
--   ③ 2025-2026-1 未开启 → 只能看
-- ----------------------------
INSERT INTO `selection_round` (`round_name`, `term`, `select_start`, `select_end`, `status`, `max_credits`)
VALUES ('2024-2025-1 第一轮选课', '2024-2025-1',
        DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY), 1, 30.0);

INSERT INTO `selection_round` (`round_name`, `term`, `select_start`, `select_end`, `drop_start`, `drop_end`, `status`, `max_credits`)
VALUES ('2024-2025-2 补退选', '2024-2025-2',
        DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY),
        DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_ADD(NOW(), INTERVAL 10 DAY), 1, 30.0);

INSERT INTO `selection_round` (`round_name`, `term`, `select_start`, `select_end`, `status`, `max_credits`)
VALUES ('2025-2026-1 第一轮选课（未开启）', '2025-2026-1',
        DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY), 0, 30.0);

INSERT INTO `selection_round_scope` (`round_id`, `grade`, `major_id`, `college_id`)
SELECT id, '2023', NULL, NULL FROM `selection_round`
WHERE `round_name` = '2025-2026-1 第一轮选课（未开启）';

-- ----------------------------
-- Table structure for exam_schedule
-- 考试安排（P4）
-- ⚠️ 时间冲突判据与 P2 的节次冲突不同：节次是离散格子（闭区间），
--    考试是连续时钟区间（半开区间，"前一场 12:00 结束/后一场 12:00 开始"不算冲突）。
-- ----------------------------
DROP TABLE IF EXISTS `exam_schedule`;
CREATE TABLE `exam_schedule`  (
  `id` int NOT NULL AUTO_INCREMENT,
  `course_id` int NOT NULL,
  `exam_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'FINAL',
  `exam_time` datetime NOT NULL,
  `duration_minutes` int NOT NULL DEFAULT 120,
  `room_id` int NULL DEFAULT NULL,
  `seat_range` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `invigilator` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_exam_course`(`course_id` ASC) USING BTREE,
  INDEX `idx_exam_time`(`exam_time` ASC) USING BTREE,
  INDEX `idx_exam_room`(`room_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of exam_schedule
-- 种子考试**不放在这里**：考试按 course_code 反查 course.id，而本文件只建了 CS101/CS102
-- 两门课（其余 10 门在 seed_data.sql 里）。放在本文件会因查不到课程而写入 NULL 主键失败。
-- 见 seed_data.sql 第 11 节。
-- ----------------------------

-- ----------------------------
-- Table structure for academic_warning（学业预警已读水位线，JW-01 §5）
-- 语义见 docs/sql/2026-09-20-academic-warning-migration.sql：
-- 只在学生点"我知道了"时插一行（记录当时的未通过学分累计与阈值快照），
-- 之后当前值超过该水位线才再次提示 —— 即"只弹一次，情况变严重才再弹"。
-- ----------------------------
DROP TABLE IF EXISTS `academic_warning`;
CREATE TABLE `academic_warning`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `student_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '学号',
  `failed_credits` decimal(6,2) NOT NULL COMMENT '确认时的未通过学分累计（水位线）',
  `failed_course_count` int NOT NULL DEFAULT 0 COMMENT '确认时的未通过课程门数',
  `threshold` decimal(6,2) NOT NULL COMMENT '确认时的预警阈值（快照）',
  `read_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '学生确认（已读）时间',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_warning_student_credits`(`student_id` ASC, `failed_credits` ASC) USING BTREE,
  INDEX `idx_warning_student`(`student_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic COMMENT = '学业预警已读水位线';

-- ----------------------------
-- Table structure for score_change_log（成绩变更日志，JW-09 §4.5）
-- 语义见 docs/sql/2026-09-22-score-change-log-migration.sql：
-- 成绩新增/修改/删除都留痕，记下**改前与改后**快照、操作人、以及来源（界面 UI / 智能助手 AI）。
-- 挂钩点在 ScoreServiceImpl（全部成绩写入的必经之路），因此两条路径都覆盖。
-- ----------------------------
DROP TABLE IF EXISTS `score_change_log`;
CREATE TABLE `score_change_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '操作人（学号/工号/用户名）',
  `operator_role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '操作人角色',
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'UI' COMMENT '来源：UI 界面/接口，AI 智能助手',
  `operation` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'INSERT/UPDATE/DELETE',
  `score_id` int NULL DEFAULT NULL COMMENT '成绩记录 id',
  `course_id` int NOT NULL COMMENT '课程 id',
  `student_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '学号',
  `before_usual` decimal(6,3) NULL DEFAULT NULL,
  `before_exam` decimal(6,3) NULL DEFAULT NULL,
  `before_makeup` decimal(6,3) NULL DEFAULT NULL,
  `before_total` decimal(6,3) NULL DEFAULT NULL,
  `before_passed` tinyint NULL DEFAULT NULL,
  `after_usual` decimal(6,3) NULL DEFAULT NULL,
  `after_exam` decimal(6,3) NULL DEFAULT NULL,
  `after_makeup` decimal(6,3) NULL DEFAULT NULL,
  `after_total` decimal(6,3) NULL DEFAULT NULL,
  `after_passed` tinyint NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_score_log_student`(`student_id` ASC, `create_time` ASC) USING BTREE,
  INDEX `idx_score_log_course`(`course_id` ASC, `create_time` ASC) USING BTREE,
  INDEX `idx_score_log_operator`(`operator_id` ASC, `create_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic COMMENT = '成绩变更日志';

SET FOREIGN_KEY_CHECKS = 1;
