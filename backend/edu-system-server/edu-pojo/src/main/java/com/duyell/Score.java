package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @author 53473
 * 成绩类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Score {
    private Integer id;
    private Integer courseId;
    private String studentId;
    private BigDecimal usualScore;
    private BigDecimal examScore;
    private BigDecimal totalScore;

    /** 补考成绩；通过则统一按 60 分计入绩点（业务规则见 docs/教务业务扩展设计.md §2.2） */
    private BigDecimal makeupScore;

    /**
     * 是否通过（含补考）：1=通过 0=未通过。
     * <p><b>派生字段</b>，只允许 ScoreService 维护 —— 分散计算必然出现
     * "分数改了、passed 没改" 的不一致（设计文档 §3.3 风险 2）。
     */
    private Integer passed;

    private String courseName;
    private String studentName;
    private String term;

    /** 课程代码（联表查询带出）。同一门课多次开课共用同一代码，是「是否已修过」的判据 */
    private String courseCode;

    /** 学分（联表查询带出），用于已修学分与绩点加权 */
    private BigDecimal credit;
}
