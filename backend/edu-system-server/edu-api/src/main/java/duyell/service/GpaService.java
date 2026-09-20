package duyell.service;

import java.math.BigDecimal;

/**
 * 绩点计算服务。
 *
 * <p><b>业务规则权威定义见 {@code docs/教务业务扩展设计.md} §2.1</b>，
 * 本接口是该规则在代码中的唯一实现处。REST 接口与 Agent 工具都必须调用这里，
 * 不得各自实现一份。
 *
 * @author duyell
 */
public interface GpaService {

    /**
     * 单科绩点。
     *
     * <pre>
     * 若 分数 &lt; 及格线(默认60):  绩点 = 0
     * 否则:                      绩点 = 分数 / 10 - 5
     * </pre>
     *
     * <p>用户提供的验收例子（全部必须通过）：
     * 60→1.0、60.1→1.01、62.3→1.23、65→1.5、65.333→1.5333、75→2.5、75.76→2.576、
     * 85→3.5、87.365→3.7365、95→4.5、100→5.0、59.9→0。
     *
     * <p>⚠️ 必须用 BigDecimal 精确计算，**禁用 double**（double 会把 3.7365 算成 3.7365000000000002）。
     *
     * @param score 总成绩（可空，空视为未通过）
     * @return 绩点；分数为 null 时返回 {@link BigDecimal#ZERO}
     */
    BigDecimal gradePoint(BigDecimal score);

    /**
     * 判定某条成绩是否通过（含补考）。
     *
     * <pre>
     * 通过 = (总成绩 &gt;= 及格线) 或 (补考成绩 &gt;= 及格线)
     * </pre>
     *
     * <p>见设计文档 §2.2：不及格不算修完、不计学分；补考通过一律记 60 分。
     */
    boolean isPassed(BigDecimal totalScore, BigDecimal makeupScore);

    /**
     * 计算某学生的平均学分绩点。
     *
     * <pre>
     * GPA = Σ(单科绩点 × 学分) / Σ(学分)      ← 只统计「已通过」的课程
     * </pre>
     *
     * <p>补考通过的课程按 60 分计（即绩点 1.0）。
     *
     * @param studentId 学号
     * @param term      限定学期；null 或空表示累计（全部学期）
     * @return 绩点结果（含明细）；无任何已通过课程时 GPA 为 0
     */
    GpaResult calcGpa(String studentId, String term);

    /**
     * 绩点计算结果。
     *
     * @param gpa             平均学分绩点
     * @param totalCredit     计入 GPA 的总学分（只含已通过课程）
     * @param totalGradePoint 加权绩点之和 Σ(绩点×学分)
     * @param passedCount     计入的课程门数
     * @param details         逐门明细
     */
    record GpaResult(BigDecimal gpa, BigDecimal totalCredit, BigDecimal totalGradePoint,
                     int passedCount, java.util.List<CourseGpa> details) {
    }

    /**
     * 单门课程的绩点明细。
     *
     * @param courseCode 课程代码
     * @param courseName 课程名
     * @param credit     学分
     * @param rawScore   原始总成绩
     * @param usedScore  **计入绩点所用的分数**：补考通过时为 60，否则为原始成绩
     * @param gradePoint 该课绩点
     * @param fromMakeup 是否通过补考通过
     */
    record CourseGpa(String courseCode, String courseName, BigDecimal credit,
                     BigDecimal rawScore, BigDecimal usedScore, BigDecimal gradePoint,
                     boolean fromMakeup) {
    }
}
