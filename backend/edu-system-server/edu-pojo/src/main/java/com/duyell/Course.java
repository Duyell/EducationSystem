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
