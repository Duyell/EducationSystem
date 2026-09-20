package duyell.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 学分与毕业审核服务。
 *
 * <p>规则来源：{@code docs/教务业务扩展设计.md} §2.2（及格/补考/已修学分）与 §4.3(3)（毕业审核）。
 *
 * @author duyell
 */
public interface CreditService {

    /**
     * 已修学分：只累计「已通过」（含补考通过）课程的学分。
     *
     * <p>同一课程代码只计一次（不同学期重复开课不重复计分）。
     *
     * @param studentId 学号
     * @return 已修学分合计
     */
    BigDecimal earnedCredits(String studentId);

    /**
     * 毕业学分审核。
     *
     * <p>判定逻辑（设计文档 §4.3(3)）：
     * <ol>
     *   <li>定位学生适用的培养计划（专业 + 入学年级）</li>
     *   <li>必修：方案中 category=REQUIRED 的课程是否**全部通过**（逐门判定）</li>
     *   <li>选修：已通过的选修课学分总和 ≥ 方案 elective_credits</li>
     *   <li>总学分：已修总学分 ≥ 方案 total_credits</li>
     * </ol>
     *
     * @param studentId 学号
     * @return 审核结果；学生无适用培养计划时 {@code planFound=false} 且不报错
     */
    AuditResult auditGraduation(String studentId);

    /**
     * 毕业审核结果。
     *
     * @param planFound            是否找到了学生适用的培养计划
     * @param planName             培养计划名（planFound=false 时为 null）
     * @param creditSatisfied      已修总学分是否达标
     * @param requiredSatisfied    必修课是否全部通过
     * @param electiveSatisfied    选修学分是否达标
     * @param earnedCredits        已修总学分
     * @param requiredCredits      方案要求的必修学分
     * @param electiveCredits      方案要求的选修学分
     * @param totalCredits         方案要求的总学分
     * @param earnedRequiredCredit 已通过的必修学分
     * @param earnedElectiveCredit 已通过的选修学分
     * @param missingRequired      未通过的必修课清单
     * @param unmetElectiveCodes   方案内尚未通过的可选课程代码（供"推荐选修"使用）
     */
    record AuditResult(
            boolean planFound,
            String planName,
            boolean creditSatisfied,
            boolean requiredSatisfied,
            boolean electiveSatisfied,
            BigDecimal earnedCredits,
            BigDecimal requiredCredits,
            BigDecimal electiveCredits,
            BigDecimal totalCredits,
            BigDecimal earnedRequiredCredit,
            BigDecimal earnedElectiveCredit,
            List<MissingCourse> missingRequired,
            List<String> unmetElectiveCodes
    ) {
        /** 是否全部满足（可毕业） */
        public boolean satisfied() {
            return planFound && creditSatisfied && requiredSatisfied && electiveSatisfied;
        }

        /** 距离总学分要求的缺口（已达标则为 0） */
        public BigDecimal creditGap() {
            BigDecimal gap = totalCredits.subtract(earnedCredits);
            return gap.compareTo(BigDecimal.ZERO) > 0 ? gap : BigDecimal.ZERO;
        }
    }

    /**
     * 未通过的必修课。
     *
     * @param courseCode 课程代码
     * @param courseName 课程名
     * @param credit     学分
     * @param suggestSemester 建议修读学期
     * @param state      状态：NOT_TAKEN 未修读 / FAILED 已修但未通过
     */
    record MissingCourse(String courseCode, String courseName, BigDecimal credit,
                         Integer suggestSemester, String state) {
        public static final String STATE_NOT_TAKEN = "NOT_TAKEN";
        public static final String STATE_FAILED = "FAILED";
    }
}
