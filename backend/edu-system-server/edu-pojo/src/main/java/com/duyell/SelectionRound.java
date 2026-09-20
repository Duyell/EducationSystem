package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 选课轮次（管理员控制开关）。
 *
 * <p>用户明确的规则（docs/教务业务扩展设计.md §2）：
 * <ul>
 *   <li>只有管理员**开启选课**（{@code status=1}）后学生才能选；否则**只能看**</li>
 *   <li>选课持续期间**可以退**，否则要等**补退选**（{@code dropStart}~{@code dropEnd}）期间才能退</li>
 * </ul>
 *
 * <p>因此「轮次开启」与「时间窗」是**两个独立条件**，都要满足：
 * status=1 只表示管理员打开了开关，还要看当前时间落没落在窗口里。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelectionRound {

    private Integer id;

    /** 如：2024-2025-1 第一轮选课 */
    private String roundName;

    /** 适用学期 */
    private String term;

    /** 选课开放开始 */
    private LocalDateTime selectStart;

    /** 选课开放结束 */
    private LocalDateTime selectEnd;

    /** 补退选开始，可空 */
    private LocalDateTime dropStart;

    /** 补退选结束，可空 */
    private LocalDateTime dropEnd;

    /** 1=开启 0=关闭 */
    private Integer status;

    /** 本轮学分上限，可空=不限 */
    private BigDecimal maxCredits;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---- 展示/装配用（不落库） ----
    /** 适用范围；空列表表示"不限" */
    private List<SelectionRoundScope> scopes;

    public static final int STATUS_OPEN = 1;
    public static final int STATUS_CLOSED = 0;
}
