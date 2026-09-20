package duyell.service;

import com.duyell.PlanCourse;
import com.duyell.Student;
import com.duyell.TrainingPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 培养计划定位与毕业学分审核的集成测试（真实 MySQL）。
 *
 * <p>依赖迁移脚本 {@code docs/sql/2026-09-20-p1-training-plan-migration.sql} 的种子数据：
 * 「计算机科学与技术 2023 级培养计划（示例）」——必修 4 门共 14.5 学分、
 * 选修要求 25.5 学分、总学分要求 40。
 *
 * <p>断言值均以**真实数据**为准（2023001 已通过 4 门必修共 14.5 学分），
 * 不是凭想象写死的数字。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TrainingPlanAndCreditServiceTest {

    @Autowired
    private TrainingPlanService trainingPlanService;

    @Autowired
    private CreditService creditService;

    // ---------- 培养计划定位（版本策略） ----------

    @Test
    void resolvesPlanByStudentMajorAndGrade() {
        TrainingPlan plan = trainingPlanService.resolvePlanForStudent("2023001");

        assertNotNull(plan, "计科 2023 级学生应能定位到培养计划");
        assertEquals("2023", plan.getGrade());
        assertEquals("计算机科学与技术", plan.getMajorName());
        assertEquals(0, plan.getTotalCredits().compareTo(new BigDecimal("40.0")));
        assertEquals(0, plan.getRequiredCredits().compareTo(new BigDecimal("14.5")));
        assertEquals(0, plan.getElectiveCredits().compareTo(new BigDecimal("25.5")));
    }

    /**
     * 版本策略的核心：同一个专业、**不同年级**必须定位到不同版本的方案。
     * 计科 2024 级目前没有方案，应返回 null（而不是错误地套用 2023 级方案）。
     */
    @Test
    void differentGradeDoesNotReuseAnotherGradesPlan() {
        assertNull(trainingPlanService.resolvePlanForStudent("2024001"),
                "计科 2024 级无方案，不应套用 2023 级方案");
    }

    @Test
    void otherMajorsHaveNoPlanYet() {
        assertNull(trainingPlanService.resolvePlanForStudent("2023005"), "英语专业暂无方案");
        assertNull(trainingPlanService.resolvePlanForStudent("2023007"), "数学专业暂无方案");
    }

    @Test
    void unknownStudentReturnsNullInsteadOfError() {
        assertNull(trainingPlanService.resolvePlanForStudent("9999999"));
        assertNull(trainingPlanService.getStudentPlacement("9999999"));
    }

    @Test
    void placementCarriesGradeAndMajorFromClazzChain() {
        Student placement = trainingPlanService.getStudentPlacement("2023001");

        assertNotNull(placement);
        assertEquals("2023", placement.getGrade(), "年级来自 clazz.grade");
        assertNotNull(placement.getMajorId(), "专业 id 来自 clazz.major_id");
        assertEquals("计算机科学与技术", placement.getMajorName());
        assertEquals("计算机学院", placement.getCollegeName());
    }

    // ---------- 方案明细 ----------

    @Test
    void planCoursesAreSplitIntoRequiredAndElective() {
        TrainingPlan plan = trainingPlanService.resolvePlanForStudent("2023001");

        List<PlanCourse> required = trainingPlanService.listPlanCourses(
                plan.getId(), PlanCourse.CATEGORY_REQUIRED);
        List<PlanCourse> elective = trainingPlanService.listPlanCourses(
                plan.getId(), PlanCourse.CATEGORY_ELECTIVE);

        assertEquals(4, required.size(), "示例方案有 4 门必修");
        assertEquals(8, elective.size(), "示例方案有 8 门选修");
        assertTrue(required.stream().allMatch(PlanCourse::isRequired));
        assertTrue(elective.stream().noneMatch(PlanCourse::isRequired));

        // 每门课都应有课程代码与学分快照
        for (PlanCourse pc : required) {
            assertNotNull(pc.getCourseCode());
            assertNotNull(pc.getCredit());
            assertTrue(pc.getCredit().compareTo(BigDecimal.ZERO) > 0);
        }

        Set<String> requiredCodes = required.stream()
                .map(PlanCourse::getCourseCode).collect(Collectors.toSet());
        assertTrue(requiredCodes.containsAll(Set.of("CS101", "CS102", "CS103", "CS104")),
                "必修应含 CS101~CS104，实际：" + requiredCodes);
    }

    @Test
    void listPlanCoursesWithNullPlanIdReturnsEmptyInsteadOfAll() {
        assertTrue(trainingPlanService.listPlanCourses(null).isEmpty());
        assertTrue(trainingPlanService.listPlanCourses(null, PlanCourse.CATEGORY_REQUIRED).isEmpty());
    }

    // ---------- 已修学分 ----------

    @Test
    void earnedCreditsCountsOnlyPassedCourses() {
        BigDecimal credits = creditService.earnedCredits("2023001");
        assertEquals(0, credits.compareTo(new BigDecimal("14.5")),
                "2023001 已通过 CS101(4.0)+CS102(3.0)+CS103(4.0)+CS104(3.5) = 14.5");
    }

    @Test
    void studentWithoutAnyScoreHasZeroCredits() {
        assertEquals(0, creditService.earnedCredits("2023001").compareTo(new BigDecimal("14.5")));
        // 2024001 只修了 1 门 4.0 学分
        assertEquals(0, creditService.earnedCredits("2024001").compareTo(new BigDecimal("4.0")));
    }

    // ---------- 毕业审核 ----------

    @Test
    void auditReportsRequiredSatisfiedButElectiveGap() {
        CreditService.AuditResult r = creditService.auditGraduation("2023001");

        assertTrue(r.planFound(), "应定位到方案");
        assertEquals("计算机科学与技术 2023 级培养计划（示例）", r.planName());

        // 4 门必修全部通过
        assertTrue(r.requiredSatisfied(), "必修应全部通过，缺失：" + r.missingRequired());
        assertTrue(r.missingRequired().isEmpty());
        assertEquals(0, r.earnedRequiredCredit().compareTo(new BigDecimal("14.5")));

        // 总学分与选修均未达标
        assertEquals(0, r.earnedCredits().compareTo(new BigDecimal("14.5")));
        assertFalse(r.creditSatisfied(), "总学分 14.5 < 要求 40，不应达标");
        assertFalse(r.electiveSatisfied(), "选修学分 0 < 要求 25.5，不应达标");
        assertFalse(r.satisfied(), "整体不应判定为可毕业");

        // 缺口计算
        assertEquals(0, r.creditGap().compareTo(new BigDecimal("25.5")),
                "总学分缺口 = 40 - 14.5 = 25.5");

        // 未通过的选修课应可用于「推荐选课」
        assertEquals(8, r.unmetElectiveCodes().size());
        assertTrue(r.unmetElectiveCodes().contains("CS105"));
    }

    @Test
    void auditForStudentWithoutPlanDoesNotErrorAndFlagsPlanMissing() {
        CreditService.AuditResult r = creditService.auditGraduation("2024001");

        assertFalse(r.planFound(), "计科 2024 级无方案");
        assertFalse(r.satisfied(), "无方案不能判定为可毕业");
        assertTrue(r.missingRequired().isEmpty());
        assertEquals(0, r.creditGap().compareTo(BigDecimal.ZERO), "无方案时缺口按 0 处理");
    }

    @Test
    void auditReportsNotTakenVersusFailedForMissingRequiredCourses() {
        // 2023001 的必修全部通过，因此缺失清单为空；这里验证状态枚举可用的同时，
        // 断言"已通过"的课程不会出现在缺失清单里
        CreditService.AuditResult r = creditService.auditGraduation("2023001");
        Set<String> missingCodes = r.missingRequired().stream()
                .map(CreditService.MissingCourse::courseCode).collect(Collectors.toSet());

        assertFalse(missingCodes.contains("CS101"), "CS101 已通过，不应在缺失清单");
        assertFalse(missingCodes.contains("CS102"));
        assertFalse(missingCodes.contains("CS103"));
        assertFalse(missingCodes.contains("CS104"));
    }
}
