package duyell.service.impl;

import com.duyell.GpaRule;
import com.duyell.Score;
import com.duyell.Student;
import duyell.mapper.GpaRuleMapper;
import duyell.mapper.ScoreMapper;
import duyell.mapper.StudentMapper;
import duyell.service.GpaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import utils.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link GpaService} 实现。
 *
 * <p>规则来源：{@code docs/教务业务扩展设计.md} §2.1（已与用户逐例确认）。
 * 本类是该业务规则的**唯一实现处**。
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GpaServiceImpl implements GpaService {

    /** 绩点计算精度。用户例子含 3 位小数分数（87.365），除以 10 后需再留足精度 */
    private static final int GP_SCALE = 6;

    /** GPA 结果精度 */
    private static final int GPA_SCALE = 4;

    private static final BigDecimal TEN = BigDecimal.valueOf(10);
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);

    /** 及格线兜底值。仅当 gpa_rule 表未配置时使用 */
    private static final BigDecimal DEFAULT_PASS_SCORE = BigDecimal.valueOf(60);

    private final ScoreMapper scoreMapper;
    private final GpaRuleMapper gpaRuleMapper;
    private final StudentMapper studentMapper;

    @Override
    public BigDecimal gradePoint(BigDecimal score) {
        if (score == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal passScore = currentPassScore();
        if (score.compareTo(passScore) < 0) {
            return BigDecimal.ZERO;
        }
        // 规则：分数/10 - 5。例：60→1.0、75.76→2.576、87.365→3.7365、100→5.0
        return score.divide(TEN, GP_SCALE, RoundingMode.HALF_UP).subtract(FIVE);
    }

    @Override
    public boolean isPassed(BigDecimal totalScore, BigDecimal makeupScore) {
        BigDecimal passScore = currentPassScore();
        boolean rawPassed = totalScore != null && totalScore.compareTo(passScore) >= 0;
        boolean makeupPassed = makeupScore != null && makeupScore.compareTo(passScore) >= 0;
        return rawPassed || makeupPassed;
    }

    @Override
    public GpaResult calcGpa(String studentId, String term) {
        List<Score> scores = scoreMapper.list(null, requireNumericStudentId(studentId), blankToNull(term));
        if (scores == null || scores.isEmpty()) {
            return emptyResult();
        }

        // 同一课程代码可能有多条成绩（不同学期重复开课），取绩点最高的一条计入，
        // 避免"重修拉低 GPA"这类争议（设计文档未规定，此处按对学生有利处理并记录）
        List<CourseGpa> details = new ArrayList<>();
        java.util.Map<String, CourseGpa> bestByCourse = new java.util.LinkedHashMap<>();

        for (Score s : scores) {
            BigDecimal used = effectiveScore(s);
            if (!isPassed(s.getTotalScore(), s.getMakeupScore())) {
                continue; // 不及格不计入 GPA（设计文档 §2.1）
            }
            BigDecimal credit = s.getCredit() == null ? BigDecimal.ZERO : s.getCredit();
            BigDecimal gp = gradePoint(used);
            boolean fromMakeup = !isPassed(s.getTotalScore(), null)
                    && isPassed(null, s.getMakeupScore());
            String code = s.getCourseCode() != null ? s.getCourseCode() : ("#" + s.getCourseId());

            CourseGpa candidate = new CourseGpa(code, s.getCourseName(), credit,
                    s.getTotalScore(), used, gp, fromMakeup);

            CourseGpa existing = bestByCourse.get(code);
            if (existing == null || gp.compareTo(existing.gradePoint()) > 0) {
                bestByCourse.put(code, candidate);
            }
        }
        details.addAll(bestByCourse.values());

        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalGradePoint = BigDecimal.ZERO;
        for (CourseGpa d : details) {
            totalCredit = totalCredit.add(d.credit());
            totalGradePoint = totalGradePoint.add(d.gradePoint().multiply(d.credit()));
        }

        BigDecimal gpa = totalCredit.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalGradePoint.divide(totalCredit, GPA_SCALE, RoundingMode.HALF_UP);

        return new GpaResult(gpa, totalCredit, totalGradePoint, details.size(), details);
    }

    @Override
    public RankResult rankInMajor(String studentId) {
        Student placement = studentMapper.selectWithGradeAndMajor(studentId);
        if (placement == null || placement.getMajorId() == null
                || placement.getGrade() == null || placement.getGrade().isBlank()) {
            log.info("缺少专业或年级信息，无法排名: studentId={}", studentId);
            return new RankResult(false, null, null, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, currentPassScore());
        }

        List<String> peers = studentMapper.listStudentIdsByMajorAndGrade(
                placement.getMajorId(), placement.getGrade());
        if (peers == null || peers.isEmpty()) {
            return new RankResult(false, placement.getMajorName(), placement.getGrade(), 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, currentPassScore());
        }

        // 计算同专业同年级每个人的绩点并降序排序；只保留名次，不返回他人明细
        List<BigDecimal> gpas = new ArrayList<>(peers.size());
        for (String peer : peers) {
            gpas.add(calcGpaQuietly(peer));
        }
        gpas.sort(Comparator.reverseOrder());

        BigDecimal mine = calcGpaQuietly(studentId);
        // 名次 = 绩点高于我的人数 + 1（并列取最好名次）
        int better = 0;
        for (BigDecimal g : gpas) {
            if (g.compareTo(mine) > 0) {
                better++;
            }
        }

        GpaResult myGpa = calcGpa(studentId, null);
        return new RankResult(true, placement.getMajorName(), placement.getGrade(),
                better + 1, gpas.size(), mine, myGpa.totalCredit(),
                gpas.get(0), currentPassScore());
    }

    /**
     * 排名时逐个计算同学绩点：任一同学数据异常不应让整个排名失败。
     * 异常时按 0 计入（该同学排到后面），并告警。
     */
    private BigDecimal calcGpaQuietly(String studentId) {
        try {
            return calcGpa(studentId, null).gpa();
        } catch (Exception e) {
            log.warn("计算学生绩点失败，排名时按 0 处理: studentId={}", studentId, e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 计入绩点所用的分数：补考通过时统一按 60 分（设计文档 §2.2）。
     */
    private BigDecimal effectiveScore(Score s) {
        boolean rawPassed = isPassed(s.getTotalScore(), null);
        if (rawPassed) {
            return s.getTotalScore();
        }
        if (isPassed(null, s.getMakeupScore())) {
            return currentPassScore(); // 补考通过一律记 60
        }
        return s.getTotalScore();
    }

    private GpaResult emptyResult() {
        return new GpaResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of());
    }

    /**
     * 取当前及格线。
     *
     * <p>规则表未配置时用兜底值 60 并告警 —— 不因为配置缺失就让绩点功能整体不可用，
     * 但必须让人知道用的是兜底值（静默降级比报错更危险）。
     */
    private BigDecimal currentPassScore() {
        GpaRule rule = gpaRuleMapper.selectEnabled();
        if (rule == null || rule.getPassScore() == null) {
            log.warn("gpa_rule 未配置启用规则，绩点计算使用兜底及格线 {}", DEFAULT_PASS_SCORE);
            return DEFAULT_PASS_SCORE;
        }
        if (!GpaRule.TYPE_FORMULA.equals(rule.getRuleType())) {
            log.warn("gpa_rule 规则类型为 {}，当前仅实现 FORMULA，将按 FORMULA 计算", rule.getRuleType());
        }
        return rule.getPassScore();
    }

    /**
     * 把学号转成 {@code ScoreMapper.list} 需要的 Integer。
     *
     * <p>⚠️ 这里**必须显式报错，不能返回 null** —— {@code ScoreMapper.list} 的参数为 null 时
     * 该条件不会拼进 SQL，会变成**查全表所有学生的成绩**，静默产出错误结果。
     * （历史遗留：score.student_id 是 varchar，但 mapper 签名用了 Integer。）
     */
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

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
