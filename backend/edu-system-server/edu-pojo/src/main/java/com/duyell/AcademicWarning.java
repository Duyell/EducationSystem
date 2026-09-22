package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学业预警「已读水位线」记录（表 {@code academic_warning}）。
 *
 * <p>语义见 {@code docs/sql/2026-09-20-academic-warning-migration.sql}：
 * 只在学生点"我知道了"时插一行，记录**当时**的未通过学分累计与阈值快照；
 * 之后当前值超过这条水位线才再次提示（只弹一次，情况变严重才再弹）。
 *
 * <p>制度依据：{@code docs/policies/01-学籍管理规定.md} §5（v1.2）。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcademicWarning {

    private Long id;

    /** 学号 */
    private String studentId;

    /** 确认时的未通过学分累计（水位线） */
    private BigDecimal failedCredits;

    /** 确认时的未通过课程门数 */
    private Integer failedCourseCount;

    /** 确认时的预警阈值（快照，便于回溯当时按什么标准预警） */
    private BigDecimal threshold;

    /** 学生确认（已读）时间 */
    private LocalDateTime readAt;

    private LocalDateTime createTime;
}
