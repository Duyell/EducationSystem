package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 绩点换算规则（校规，做成可配置表）。
 *
 * <p>做成表而非硬编码的理由：绩点换算是**校规**，各校不同且可能调整。
 * 本轮代码只实现 {@code FORMULA} 类型；将来要支持按分数段查表（{@code TABLE}）
 * 也不需要改表结构。
 *
 * <p>见 docs/教务业务扩展设计.md §2.1 与 §3.2(3)
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GpaRule {

    /** 规则类型：公式 */
    public static final String TYPE_FORMULA = "FORMULA";

    /** 规则类型：分段表（本轮未实现） */
    public static final String TYPE_TABLE = "TABLE";

    private Integer id;

    private String ruleName;

    /** 及格线，默认 60。既用于绩点（低于此值为 0）也用于「是否通过」判定 */
    private BigDecimal passScore;

    /** {@link #TYPE_FORMULA} 或 {@link #TYPE_TABLE} */
    private String ruleType;

    /** 公式说明（给人看的，代码不解析） */
    private String formula;

    /** 1=启用（同时只允许一条启用） */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
