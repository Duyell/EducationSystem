package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 选课轮次适用范围。
 *
 * <p>三个条件都可空，**NULL = 不限**；一条都不填表示"全年级全专业都可选"。
 * 设计理由：不同年级/专业的选课时间通常不同（如毕业班优先）。
 * 本轮只做"限定范围"，**不做优先级排序**（docs/教务业务扩展设计.md §3.2(8)）。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelectionRoundScope {

    private Integer id;

    /** 关联 selection_round.id */
    private Integer roundId;

    /** 限定年级，NULL=不限 */
    private String grade;

    /** 限定专业，NULL=不限 */
    private Integer majorId;

    /** 限定学院，NULL=不限 */
    private Integer collegeId;

    // ---- 展示用冗余字段（不落库） ----
    private String majorName;
    private String collegeName;

    /** 三个条件全空 = 不限（这类记录等同"不限制"） */
    public boolean isUnrestricted() {
        return (grade == null || grade.isBlank()) && majorId == null && collegeId == null;
    }

    /**
     * 判断某个学生是否落在这条范围内。
     *
     * <p>逐项匹配：填了的条件必须相等，没填的不参与判断（NULL=不限）。
     * 学生缺少专业/年级信息时，**只要范围里填了这一项就算不匹配**——
     * 宁可少放行，也不要把范围校验变成摆设。
     */
    public boolean matches(String studentGrade, Integer studentMajorId, Integer studentCollegeId) {
        if (grade != null && !grade.isBlank() && !grade.equals(studentGrade)) {
            return false;
        }
        if (majorId != null && !majorId.equals(studentMajorId)) {
            return false;
        }
        if (collegeId != null && !collegeId.equals(studentCollegeId)) {
            return false;
        }
        return true;
    }
}
