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

    private String courseName;
    private String studentName;
    private String term;
}
