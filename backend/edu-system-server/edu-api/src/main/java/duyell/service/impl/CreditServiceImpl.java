package duyell.service.impl;

import com.duyell.PlanCourse;
import com.duyell.Score;
import com.duyell.TrainingPlan;
import duyell.mapper.ScoreMapper;
import duyell.service.CreditService;
import duyell.service.GpaService;
import duyell.service.TrainingPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import utils.BusinessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link CreditService} 实现。
 *
 * <p>规则来源：{@code docs/教务业务扩展设计.md} §2.2 与 §4.3。
 *
 * <p>设计要点：
 * <ul>
 *   <li>「是否通过」一律委托 {@link GpaService#isPassed}，不在此重复实现补考规则</li>
 *   <li>已修学分按**实际修读的课程**的学分累计（设计文档 §4.3(2)：
 *       学分取自 {@code course.credit} 而非方案快照）；同一课程代码只计一次</li>
 *   <li>培养方案未建立时**不报错**，而是返回 {@code planFound=false}，
 *       让上层能区分"没方案"与"不达标"</li>
 * </ul>
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final ScoreMapper scoreMapper;
    private final GpaService gpaService;
    private final TrainingPlanService trainingPlanService;

    @Override
    public BigDecimal earnedCredits(String studentId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Score s : passedScoresByCourseCode(allScores(studentId)).values()) {
            total = total.add(creditOf(s));
        }
        return total;
    }

    @Override
    public AuditResult auditGraduation(String studentId) {
        TrainingPlan plan = trainingPlanService.resolvePlanForStudent(studentId);
        if (plan == null) {
            log.info("学生无适用培养计划，毕业审核返回 planFound=false: studentId={}", studentId);
            return new AuditResult(false, null, false, false, false,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of());
        }

        // 1. 学生全部成绩（只查一次，避免 N+1）
        List<Score> allScores = allScores(studentId);
        Map<String, Score> passed = passedScoresByCourseCode(allScores);
        Set<String> passedCodes = passed.keySet();

        // 2. 方案明细
        List<PlanCourse> required = trainingPlanService.listPlanCourses(
                plan.getId(), PlanCourse.CATEGORY_REQUIRED);
        List<PlanCourse> elective = trainingPlanService.listPlanCourses(
                plan.getId(), PlanCourse.CATEGORY_ELECTIVE);

        // 3. 必修逐门判定
        List<MissingCourse> missing = new ArrayList<>();
        BigDecimal earnedRequired = BigDecimal.ZERO;
        for (PlanCourse pc : required) {
            if (passedCodes.contains(pc.getCourseCode())) {
                earnedRequired = earnedRequired.add(creditOf(passed.get(pc.getCourseCode())));
            } else {
                missing.add(new MissingCourse(pc.getCourseCode(), pc.getCourseName(), pc.getCredit(),
                        pc.getSuggestSemester(), missingState(pc, allScores)));
            }
        }

        // 4. 选修学分
        BigDecimal earnedElective = BigDecimal.ZERO;
        List<String> unmetElectiveCodes = new ArrayList<>();
        for (PlanCourse pc : elective) {
            if (passedCodes.contains(pc.getCourseCode())) {
                earnedElective = earnedElective.add(creditOf(passed.get(pc.getCourseCode())));
            } else {
                unmetElectiveCodes.add(pc.getCourseCode());
            }
        }

        // 5. 总学分（实际修读并通过的学分，可能与方案课程不完全对应）
        BigDecimal earnedTotal = earnedCreditsOf(passed);
        boolean creditSatisfied = earnedTotal.compareTo(plan.getTotalCredits()) >= 0;
        boolean requiredSatisfied = missing.isEmpty();
        boolean electiveSatisfied = earnedElective.compareTo(plan.getElectiveCredits()) >= 0;

        return new AuditResult(true, plan.getPlanName(), creditSatisfied, requiredSatisfied,
                electiveSatisfied, earnedTotal, plan.getRequiredCredits(), plan.getElectiveCredits(),
                plan.getTotalCredits(), earnedRequired, earnedElective, missing, unmetElectiveCodes);
    }

    /**
     * 区分「从未修读」与「修了但没通过」。
     *
     * <p>对用户而言这两者可操作的动作不同：前者要选课，后者要补考。
     *
     * @param allScores 该生全部成绩（由调用方一次查出后传入，避免 N+1）
     */
    private String missingState(PlanCourse planCourse, List<Score> allScores) {
        for (Score s : allScores) {
            if (planCourse.getCourseCode() != null
                    && planCourse.getCourseCode().equals(s.getCourseCode())) {
                return MissingCourse.STATE_FAILED; // 有成绩记录但未通过
            }
        }
        return MissingCourse.STATE_NOT_TAKEN;
    }

    /** 已通过课程，按课程代码归并（同一代码多次修读取第一条已通过记录） */
    private Map<String, Score> passedScoresByCourseCode(List<Score> allScores) {
        Map<String, Score> result = new LinkedHashMap<>();
        for (Score s : allScores) {
            if (!gpaService.isPassed(s.getTotalScore(), s.getMakeupScore())) {
                continue;
            }
            String code = s.getCourseCode() != null ? s.getCourseCode() : ("#" + s.getCourseId());
            result.putIfAbsent(code, s);
        }
        return result;
    }

    private BigDecimal earnedCreditsOf(Map<String, Score> passedByCode) {
        BigDecimal total = BigDecimal.ZERO;
        for (Score s : passedByCode.values()) {
            total = total.add(creditOf(s));
        }
        return total;
    }

    private BigDecimal creditOf(Score s) {
        return s.getCredit() == null ? BigDecimal.ZERO : s.getCredit();
    }

    /**
     * 取学生全部成绩。
     *
     * <p>与 {@code GpaServiceImpl} 相同的限制：{@code ScoreMapper.list} 的 studentId 为 Integer，
     * 而 {@code score.student_id} 是 varchar。此处显式校验，避免传 null 导致查全表。
     */
    private List<Score> allScores(String studentId) {
        List<Score> scores = scoreMapper.list(null, requireNumericStudentId(studentId), null);
        return scores == null ? List.of() : scores;
    }

    private Integer requireNumericStudentId(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            throw new BusinessException("学号不能为空");
        }
        try {
            return Integer.valueOf(studentId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("学号必须为纯数字（当前 score mapper 限制）：" + studentId);
        }
    }
}
