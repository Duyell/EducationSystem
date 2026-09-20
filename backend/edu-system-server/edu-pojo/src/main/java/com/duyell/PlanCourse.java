package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 培养计划课程明细。
 *
 * <p>用 {@code courseCode} 而非 {@code courseId} 关联课程：培养计划可以先于开课存在
 * （方案里写了这门课，但本学期还没开）。
 *
 * <p>{@code credit} 是学分**快照** —— 即使将来课程学分数被管理员改动，
 * 方案的学分要求也不会静默变化。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(2)
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanCourse {

    /** 课程类别：必修 */
    public static final String CATEGORY_REQUIRED = "REQUIRED";

    /** 课程类别：选修 */
    public static final String CATEGORY_ELECTIVE = "ELECTIVE";

    private Integer id;

    /** 关联 training_plan.id */
    private Integer planId;

    /** 课程代码（关联 course.course_code） */
    private String courseCode;

    /** 课程名快照 */
    private String courseName;

    /** {@link #CATEGORY_REQUIRED} 或 {@link #CATEGORY_ELECTIVE} */
    private String category;

    /** 建议修读学期序号 1~8（大一上=1），用于「大一上该上什么」 */
    private Integer suggestSemester;

    /** 学分快照 */
    private BigDecimal credit;

    private String remark;

    /** 是否必修（便于调用方判断，不落库） */
    public boolean isRequired() {
        return CATEGORY_REQUIRED.equals(category);
    }
}
