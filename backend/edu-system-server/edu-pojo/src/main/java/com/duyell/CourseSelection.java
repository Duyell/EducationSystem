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

    /**
     * 选课时命中的选课轮次；历史数据为空。
     *
     * <p>存下来是为了可追溯："这次选课是哪一轮开放的"——将来排查
     * "为什么他能选到这门课"时，看这一列就知道当时哪个轮次在生效。
     */
    private Integer roundId;

    private LocalDateTime selectTime;
}
