package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成绩变更日志（表 {@code score_change_log}）。
 *
 * <p>只增加记录、不参与任何判定。制度与动机见 {@code docs/policies/09-教师成绩录入与修改规范.md} §4.5
 * 与 {@code docs/sql/2026-09-22-score-change-log-migration.sql} 头部说明。
 *
 * <p>字段刻意分 {@code before*} 与 {@code after*} 两组：成绩申诉时最需要回答的就是
 * "**改前是多少、改后是多少**"，只记"改过"等于没记。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoreChangeLog {

    public static final String OP_INSERT = "INSERT";
    public static final String OP_UPDATE = "UPDATE";
    public static final String OP_DELETE = "DELETE";

    private Long id;

    /** 操作人（学号/工号/用户名） */
    private String operatorId;

    /** 操作人角色：student/teacher/admin */
    private String operatorRole;

    /** 来源：UI（界面或接口）/ AI（智能助手） */
    private String source;

    /** 操作：INSERT/UPDATE/DELETE */
    private String operation;

    /** 成绩记录 id（删除后仍保留） */
    private Integer scoreId;

    private Integer courseId;

    private String studentId;

    private BigDecimal beforeUsual;
    private BigDecimal beforeExam;
    private BigDecimal beforeMakeup;
    private BigDecimal beforeTotal;
    private Integer beforePassed;

    private BigDecimal afterUsual;
    private BigDecimal afterExam;
    private BigDecimal afterMakeup;
    private BigDecimal afterTotal;
    private Integer afterPassed;

    private LocalDateTime createTime;

    /** 展示用扩展字段（来自 course / student 表，便于管理员直接看） */
    private String courseName;
    private String courseCode;
    private String studentName;
}
