package duyell.service.impl;

import com.duyell.AcademicWarning;
import com.duyell.Score;
import duyell.mapper.AcademicWarningMapper;
import duyell.mapper.ScoreMapper;
import duyell.service.AcademicWarningService;
import duyell.service.GpaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import utils.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AcademicWarningService} 实现。
 *
 * <p>制度依据：{@code docs/policies/01-学籍管理规定.md} §5（v1.2）。
 * <ul>
 *   <li>口径：未通过课程学分累计 ≥ 阈值（阈值取自配置 {@code academic.warning.failed-credit-threshold}，默认 8）</li>
 *   <li>「是否通过」委托 {@link GpaService#isPassed}（补考通过记 60 的规则只有那一处实现）</li>
 *   <li>同一课程代码多条成绩（重修）：只要有**一条通过**就不算未通过；学分取该代码各条记录中的最大值</li>
 *   <li>通知：只弹一次——只有当前未通过学分**超过**已确认水位线才提示</li>
 * </ul>
 *
 * @author duyell
 */
@Slf4j
@Service
public class AcademicWarningServiceImpl implements AcademicWarningService {

    private final ScoreMapper scoreMapper;
    private final AcademicWarningMapper warningMapper;
    private final GpaService gpaService;

    /** 预警阈值（未通过课程学分累计）。默认 8 学分，可用环境变量 ACADEMIC_WARNING_THRESHOLD 覆盖 */
    private final BigDecimal threshold;

    public AcademicWarningServiceImpl(ScoreMapper scoreMapper,
                                      AcademicWarningMapper warningMapper,
                                      GpaService gpaService,
                                      @Value("${academic.warning.failed-credit-threshold:8}") BigDecimal threshold) {
        this.scoreMapper = scoreMapper;
        this.warningMapper = warningMapper;
        this.gpaService = gpaService;
        this.threshold = threshold;
    }

    @Override
    public WarningStatus statusFor(String studentId) {
        List<FailedCourse> failed = failedCourses(studentId);
        BigDecimal failedCredits = BigDecimal.ZERO;
        for (FailedCourse c : failed) {
            failedCredits = failedCredits.add(c.credit());
        }
        // 恰好等于阈值也算触发（"达到"阈值），与 JW-01 §5.2 的措辞一致
        boolean warned = failedCredits.compareTo(threshold) >= 0;

        AcademicWarning last = warningMapper.selectLatest(studentId);
        BigDecimal lastCredits = last == null ? null : last.getFailedCredits();
        LocalDateTime lastReadAt = last == null ? null : last.getReadAt();

        // 只弹一次：达到条件 且（从未确认过 或 情况比上次确认时更严重）
        boolean shouldNotify = warned && (lastCredits == null || failedCredits.compareTo(lastCredits) > 0);

        return new WarningStatus(warned, shouldNotify, failedCredits, failed.size(), threshold,
                lastCredits, lastReadAt, failed);
    }

    @Override
    public boolean markRead(String studentId) {
        WarningStatus status = statusFor(studentId);
        if (!status.warned()) {
            // 未达预警条件：没有可确认的内容，不写水位线（否则会把"未预警"也记成水位线）
            return false;
        }
        AcademicWarning warning = new AcademicWarning();
        warning.setStudentId(studentId);
        warning.setFailedCredits(status.failedCredits());
        warning.setFailedCourseCount(status.failedCourseCount());
        warning.setThreshold(status.threshold());
        warning.setReadAt(LocalDateTime.now());
        try {
            warningMapper.add(warning);
            log.info("学业预警已确认(已读): student={}, failedCredits={}, threshold={}",
                    studentId, status.failedCredits(), status.threshold());
            return true;
        } catch (DuplicateKeyException e) {
            // 同一水位线重复确认：幂等，视为成功但未新增
            log.info("学业预警水位线已存在，重复确认忽略: student={}, failedCredits={}",
                    studentId, status.failedCredits());
            return false;
        }
    }

    /**
     * 统计未通过课程。
     *
     * <p>按课程代码归并：只要有**一条**成绩通过（含补考通过）就不算未通过；
     * 学分取该代码各条记录中的**最大值**（重修学分若有调整，不让它少算）。
     */
    private List<FailedCourse> failedCourses(String studentId) {
        List<Score> scores = scoreMapper.list(null, requireNumericStudentId(studentId), null);
        if (scores == null || scores.isEmpty()) {
            return List.of();
        }
        Map<String, List<Score>> byCode = new LinkedHashMap<>();
        for (Score s : scores) {
            String code = s.getCourseCode() != null && !s.getCourseCode().isBlank()
                    ? s.getCourseCode() : ("#" + s.getCourseId());
            byCode.computeIfAbsent(code, k -> new ArrayList<>()).add(s);
        }

        List<FailedCourse> failed = new ArrayList<>();
        for (Map.Entry<String, List<Score>> entry : byCode.entrySet()) {
            List<Score> rows = entry.getValue();
            boolean anyPassed = rows.stream()
                    .anyMatch(s -> gpaService.isPassed(s.getTotalScore(), s.getMakeupScore()));
            if (anyPassed) {
                continue;
            }
            Score latest = rows.get(rows.size() - 1);
            BigDecimal credit = BigDecimal.ZERO;
            for (Score s : rows) {
                BigDecimal c = s.getCredit() == null ? BigDecimal.ZERO : s.getCredit();
                if (c.compareTo(credit) > 0) {
                    credit = c;
                }
            }
            failed.add(new FailedCourse(latest.getCourseId(), entry.getKey(), latest.getCourseName(),
                    credit, latest.getTotalScore(), latest.getMakeupScore()));
        }
        failed.sort(Comparator.comparing(FailedCourse::courseCode));
        return failed;
    }

    /**
     * 与 {@code CreditServiceImpl}/{@code GpaServiceImpl} 相同的限制：
     * {@code ScoreMapper.list} 的 studentId 是 Integer，而 {@code score.student_id} 是 varchar。
     * 显式校验以免传 null 查全表。
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
}
