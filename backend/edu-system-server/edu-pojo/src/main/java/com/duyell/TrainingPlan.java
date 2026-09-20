package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 培养计划（按「专业 + 年级」分版）。
 *
 * <p>版本策略：学生沿用其入学年级对应的版本（老生不改），后续年级可换新版。
 * 学生的适用方案由 {@code student → clazz.grade} + {@code clazz.major_id} 推导，
 * 不额外存字段。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(1)
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrainingPlan {

    private Integer id;

    /** 如：计算机科学与技术 2023 级培养计划 */
    private String planName;

    /** 关联 major.id */
    private Integer majorId;

    /** 适用年级（决定版本），如 2023 */
    private String grade;

    /** 毕业总学分要求 */
    private BigDecimal totalCredits;

    /** 必修学分要求 */
    private BigDecimal requiredCredits;

    /** 选修学分要求 */
    private BigDecimal electiveCredits;

    /** 1=启用 0=停用 */
    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---- 展示用冗余字段（不落库） ----
    /** 专业名（联表查询带出） */
    private String majorName;

    /** 学院名（联表查询带出） */
    private String collegeName;
}
