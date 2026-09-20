package duyell.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绩点计算测试 —— 规则见 docs/教务业务扩展设计.md §2.1。
 *
 * <p><b>本测试必须逐条通过用户给出的全部例子</b>。设计阶段曾把公式理解错
 * （漏了"十位之后的余数要除以 10"），用这些例子一算只对 4/13，才发现问题。
 * 因此这批断言是防止公式再次写错的第一道防线。
 *
 * <p>用真实 Spring 上下文（需要读取 gpa_rule 表中的及格线）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GpaServiceTest {

    @Autowired
    private GpaService gpaService;

    /**
     * 用户提供的全部验收例子。**任何一条不过都说明公式实现错误。**
     *
     * <p>规则：分数 &lt; 60 → 0；否则 分数/10 - 5。
     */
    @Test
    void gradePointMatchesAllUserProvidedExamples() {
        record Case(String score, String expected, String note) {
        }
        List<Case> cases = List.of(
                new Case("60", "1.0", "及格线：6-5=1"),
                new Case("60.1", "1.01", "十位后的余数除以10"),
                new Case("62.3", "1.23", ""),
                new Case("65", "1.5", ""),
                new Case("65.333", "1.5333", "3位小数"),
                new Case("75", "2.5", ""),
                new Case("75.76", "2.576", "2位小数"),
                new Case("85", "3.5", ""),
                new Case("87.365", "3.7365", "精度陷阱：double 会算出 3.7365000000000002"),
                new Case("95", "4.5", ""),
                new Case("100", "5.0", "满分：10-5=5"),
                new Case("59.9", "0", "低于及格线"),
                new Case("0", "0", "零分")
        );

        for (Case c : cases) {
            BigDecimal actual = gpaService.gradePoint(new BigDecimal(c.score()));
            assertEquals(0, actual.compareTo(new BigDecimal(c.expected())),
                    String.format("分数 %s 期望绩点 %s，实际 %s  %s", c.score(), c.expected(), actual, c.note()));
        }
    }

    @Test
    void gradePointHandlesNullAsZero() {
        assertEquals(0, gpaService.gradePoint(null).compareTo(BigDecimal.ZERO),
                "空分数应视为未通过，绩点为 0");
    }

    /**
     * 精度回归：这条专门防 double 精度问题。
     * 若实现里误用 double，实际值会是 3.7365000000000002，与期望的 3.7365 不等。
     */
    @Test
    void gradePointDoesNotSufferFloatingPointDrift() {
        BigDecimal actual = gpaService.gradePoint(new BigDecimal("87.365"));
        assertEquals("3.7365", actual.stripTrailingZeros().toPlainString(),
                "绩点必须精确等于 3.7365，不能有浮点误差");
    }

    // ---------- 通过判定（含补考） ----------

    @Test
    void rawScoreAtOrAbovePassLineIsPassed() {
        assertTrue(gpaService.isPassed(new BigDecimal("60"), null), "60 分应通过");
        assertTrue(gpaService.isPassed(new BigDecimal("60.001"), null));
        assertTrue(gpaService.isPassed(new BigDecimal("100"), null));
    }

    @Test
    void rawScoreBelowPassLineIsNotPassed() {
        assertFalse(gpaService.isPassed(new BigDecimal("59.999"), null), "59.999 不应通过");
        assertFalse(gpaService.isPassed(BigDecimal.ZERO, null));
        assertFalse(gpaService.isPassed(null, null));
    }

    @Test
    void makeupScoreAtOrAbovePassLineMakesItPassed() {
        assertTrue(gpaService.isPassed(new BigDecimal("50"), new BigDecimal("60")),
                "原成绩不及格但补考 60 分应通过");
        assertTrue(gpaService.isPassed(new BigDecimal("30"), new BigDecimal("80")),
                "补考高分也通过");
    }

    @Test
    void makeupScoreBelowPassLineIsStillNotPassed() {
        assertFalse(gpaService.isPassed(new BigDecimal("50"), new BigDecimal("59.5")),
                "补考未过仍不算通过（设计文档 §2.2）");
        assertFalse(gpaService.isPassed(new BigDecimal("50"), null),
                "未补考则只有原成绩参与判定");
    }

    // ---------- 平均学分绩点（真实库数据） ----------

    /**
     * 用真实数据算 GPA，并**手工复算**核对加权公式。
     *
     * <p>与 gpa_rule 表、score 表、course 表联动，属集成级验证。
     */
    @Test
    void calcGpaIsCreditWeightedAndExcludesFailures() {
        GpaService.GpaResult result = gpaService.calcGpa("2023001", null);

        assertNotNull(result);
        assertTrue(result.passedCount() > 0, "2023001 应有已通过课程");

        // 手工复算 Σ(绩点×学分)/Σ学分
        BigDecimal expectCredit = BigDecimal.ZERO;
        BigDecimal expectWeighted = BigDecimal.ZERO;
        for (GpaService.CourseGpa d : result.details()) {
            expectCredit = expectCredit.add(d.credit());
            expectWeighted = expectWeighted.add(d.gradePoint().multiply(d.credit()));
        }
        assertEquals(0, result.totalCredit().compareTo(expectCredit),
                "总学分应等于明细学分之和");
        assertEquals(0, result.totalGradePoint().compareTo(expectWeighted),
                "加权绩点应等于明细之和");

        if (expectCredit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal expectGpa = expectWeighted.divide(expectCredit, 4, java.math.RoundingMode.HALF_UP);
            assertEquals(0, result.gpa().compareTo(expectGpa),
                    "GPA 应等于 Σ(绩点×学分)/Σ学分");
        }

        // 每门明细的绩点应与其"计入分数"自洽
        for (GpaService.CourseGpa d : result.details()) {
            assertEquals(0, d.gradePoint().compareTo(gpaService.gradePoint(d.usedScore())),
                    "明细绩点应等于按计入分数算出的绩点：" + d.courseCode());
            assertTrue(gpaService.isPassed(d.rawScore(), d.fromMakeup() ? d.usedScore() : null),
                    "明细中的课程必须是已通过的：" + d.courseCode());
        }
    }

    @Test
    void calcGpaForUnknownStudentReturnsZeroNotError() {
        GpaService.GpaResult result = gpaService.calcGpa("9999999", null);
        assertEquals(0, result.gpa().compareTo(BigDecimal.ZERO));
        assertEquals(0, result.passedCount());
        assertTrue(result.details().isEmpty());
    }

    /** 学号非数字必须显式报错 —— 早先的实现会返回 null 参数导致查全表（静默错误结果） */
    @Test
    void nonNumericStudentIdIsRejectedInsteadOfQueryingAllRows() {
        utils.BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                utils.BusinessException.class,
                () -> gpaService.calcGpa("abc-not-numeric", null));
        assertTrue(ex.getMessage().contains("纯数字"), "应给出可理解的原因：" + ex.getMessage());
    }
}
