package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @author duyell
 * 选课表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseSelection {
    private Integer id;
    private Integer courseId;
    private String studentId;
    private LocalDateTime selectTime;
}
