package duyell.ai.tool;

/**
 * 工具风险等级 —— 决定该工具能否被模型直接执行。
 *
 * <p>READ_ONLY  只读查询，模型可自由调用。
 * <p>WRITE      写操作但可逆、影响面小（如退课可重新选回），直接执行。
 * <p>DANGEROUS  写操作且影响学业/成绩结果，必须经用户显式确认（HITL）后才执行。
 *
 * <p>安全原则：风险等级是<b>代码里的硬约束</b>，不是提示词里的一句"请先确认"。
 */
public enum RiskLevel {

    /** 只读查询 */
    READ_ONLY,

    /** 可逆的写操作，直接执行 */
    WRITE,

    /** 影响学业结果的写操作，必须人工确认 */
    DANGEROUS;

    /** 是否需要用户显式确认后才允许执行 */
    public boolean requiresConfirmation() {
        return this == DANGEROUS;
    }
}
