package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @author duyell
 * 学生类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Student {
    private Integer id;
    private String studentName;
    private String studentId;
    private Integer userId;
    private Integer clazzId;
    private String gender;
    private LocalDate birthday;
    private String phone;
    private String email;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 学籍状态：ACTIVE 在读 / SUSPENDED 休学 / WITHDRAWN 退学。
     * <p>本轮只加字段，未接入任何业务逻辑（用户确认后续再补）。
     */
    private String status;

    private String clazzName;
    private String collegeName;
    private String majorName;

    /** 年级（来自 clazz.grade，联表查询带出）——用于定位学生适用的培养计划版本 */
    private String grade;

    /** 专业 id（来自 clazz.major_id，联表查询带出） */
    private Integer majorId;

    /** 学院 id（来自 major.college_id，联表查询带出）——选课轮次的适用范围要按学院匹配 */
    private Integer collegeId;
}
