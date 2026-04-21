package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @author duyell
 * 老师类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Teacher {
    private Integer id;
    private String teacherName;
    private String teacherId;
    private Integer userId;
    private String gender;
    private LocalDate birthday;
    private String phone;
    private String email;
    private Integer collegeId;
    private String title;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private String collegeName;
}
