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
    private String clazzName;
    private String collegeName;
    private String majorName;
}
