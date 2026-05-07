package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeacherEvaluation {
    private Integer id;
    private Integer courseId;
    private String studentId;
    private String teacherId;
    private Integer score;
    private String content;
    private LocalDateTime createTime;

    private String courseName;
    private String teacherName;
    private String studentName;
}
