package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @author duyell
 * 课程类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Course {
    private Integer id;

    /**
     * 课程代码，如 CS101。
     *
     * <p>同一门课的所有开课行共用同一代码，是培养计划关联、已修判定、补考关联的唯一依据
     * （见 docs/教务业务扩展设计.md §3.1）。**由管理员在开课时填写**。
     *
     * <p>⚠️ P1 建了这一列但一直没接到 POJO/Mapper 上，导致管理端新建的课程 code 恒为 NULL，
     * 判断类功能会静默失配；P2 依赖「审批通过生成 course」故在此补齐。
     */
    private String courseCode;

    private String courseName;
    private String teacherId;
    private Integer collegeId;
    private String term;
    private BigDecimal credit;
    private Integer classHour;
    private Integer maxStudent;

    private String teacherName;
    private String collegeName;
}
