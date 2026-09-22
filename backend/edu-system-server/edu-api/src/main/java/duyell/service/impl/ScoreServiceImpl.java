package duyell.service.impl;

import com.duyell.Score;
import com.duyell.ScoreChangeLog;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.audit.ChangeContext;
import duyell.mapper.ScoreChangeLogMapper;
import duyell.mapper.ScoreMapper;
import duyell.service.GpaService;
import duyell.service.ScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import utils.BusinessException;
import utils.PageResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * @author duyell
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ScoreServiceImpl implements ScoreService {
    private final ScoreMapper scoreMapper;
    private final GpaService gpaService;
    private final ScoreChangeLogMapper changeLogMapper;

    /** 平时成绩权重 */
    private static final BigDecimal USUAL_WEIGHT = BigDecimal.valueOf(0.4);
    /** 考试成绩权重 */
    private static final BigDecimal EXAM_WEIGHT = BigDecimal.valueOf(0.6);

    /** 成绩计算精度。与 score 表的 decimal(6,3) 保持一致（设计文档 §3.3：支持 75.76 这类分数） */
    private static final int SCORE_SCALE = 3;

    /** 成绩上限：百分制（JW-05 §1.1） */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public PageResult<Score> page(Integer pageNum, Integer pageSize, Integer studentId, Integer courseId, String term) {
        Page<Score> pageResult = PageHelper.startPage(pageNum, pageSize);
        List<Score> scoreList = scoreMapper.list(courseId, studentId, term);
        return new PageResult<>(pageResult.getTotal(), scoreList);
    }

    @Override
    public void add(Score score) {
        validateRange(score);
        // 总分统一由服务端计算（平时*0.4 + 考试*0.6），不信任前端传入值
        score.setTotalScore(calcTotal(score));
        // passed 是派生字段，只在此处维护（设计文档 §3.3 风险 2）
        score.setPassed(calcPassed(score.getTotalScore(), score.getMakeupScore()));
        scoreMapper.add(score);
        // 留痕：新增（before 为空）
        writeLog(ScoreChangeLog.OP_INSERT, score.getId(), score.getCourseId(), score.getStudentId(),
                null, score);
    }

    @Override
    public void delete(Integer id) {
        Score before = scoreMapper.selectById(id);
        scoreMapper.deleteByIds(List.of(id));
        if (before != null) {
            // 留痕：删除（after 为空）——成绩被删掉后仍要能查到"曾经是多少、谁删的"
            writeLog(ScoreChangeLog.OP_DELETE, id, before.getCourseId(), before.getStudentId(),
                    before, null);
        }
    }

    @Override
    public void update(Score score) {
        // 先取原记录：既用于"只传单项时合并"，也用于留痕的 before 快照
        Score before = scoreMapper.selectById(score.getId());
        if (before == null) {
            throw new BusinessException("成绩记录不存在");
        }
        if (score.getUsualScore() == null) {
            score.setUsualScore(before.getUsualScore());
        }
        if (score.getExamScore() == null) {
            score.setExamScore(before.getExamScore());
        }
        if (score.getMakeupScore() == null) {
            score.setMakeupScore(before.getMakeupScore());
        }
        validateRange(score);
        score.setTotalScore(calcTotal(score));
        score.setPassed(calcPassed(score.getTotalScore(), score.getMakeupScore()));
        scoreMapper.update(score);
        // 留痕：修改（记下改前改后，成绩申诉时最需要的就是这两个快照）
        writeLog(ScoreChangeLog.OP_UPDATE, score.getId(), before.getCourseId(), before.getStudentId(),
                before, score);
    }

    @Override
    public Score selectById(Integer scoreId) {
        return scoreMapper.selectById(scoreId);
    }

    @Override
    public PageResult<ScoreChangeLog> changeLog(Integer pageNum, Integer pageSize,
                                                String studentId, Integer courseId, String operatorId) {
        Page<ScoreChangeLog> pageResult = PageHelper.startPage(pageNum, pageSize);
        List<ScoreChangeLog> rows = changeLogMapper.list(studentId, courseId, operatorId);
        return new PageResult<>(pageResult.getTotal(), rows);
    }

    /**
     * 写一条成绩变更日志。
     *
     * <p>操作人与来源取自 {@link ChangeContext}（由 REST / AI 两个边界设置）。
     * 日志写入失败**不应影响成绩本身**——这里刻意吞掉异常并记 error 日志：
     * 让"记不上日志"把一次正常的成绩录入变成失败，是更糟的取舍。
     */
    private void writeLog(String operation, Integer scoreId, Integer courseId, String studentId,
                          Score before, Score after) {
        try {
            ChangeContext ctx = ChangeContext.current();
            ScoreChangeLog log = new ScoreChangeLog();
            log.setOperatorId(ctx.operatorId());
            log.setOperatorRole(ctx.operatorRole());
            log.setSource(ctx.source());
            log.setOperation(operation);
            log.setScoreId(scoreId);
            log.setCourseId(courseId);
            log.setStudentId(studentId);
            if (before != null) {
                log.setBeforeUsual(before.getUsualScore());
                log.setBeforeExam(before.getExamScore());
                log.setBeforeMakeup(before.getMakeupScore());
                log.setBeforeTotal(before.getTotalScore());
                log.setBeforePassed(before.getPassed());
            }
            if (after != null) {
                log.setAfterUsual(after.getUsualScore());
                log.setAfterExam(after.getExamScore());
                log.setAfterMakeup(after.getMakeupScore());
                log.setAfterTotal(after.getTotalScore());
                log.setAfterPassed(after.getPassed());
            }
            changeLogMapper.add(log);
        } catch (Exception e) {
            log.error("成绩变更日志写入失败（不影响成绩本身）: operation={}, course={}, student={}",
                    operation, courseId, studentId, e);
        }
    }

    /**
     * 成绩取值范围校验：0~100（可空＝尚未录入）。
     *
     * <p>2026-09-22 按作者确认补上。此前**完全没有范围校验**（数据库只是 {@code decimal(6,3)}），
     * 录 999 分也能存下去。放在 service 是刻意的：界面与 AI 助手两条录入路径都必须过这里
     * （AI 工具的 Schema 里另加了 0~100，但 Schema 只是提示，**服务端才是硬闸门**）。
     */
    private static void validateRange(Score score) {
        checkOne("平时成绩", score.getUsualScore());
        checkOne("考试成绩", score.getExamScore());
        checkOne("补考成绩", score.getMakeupScore());
    }

    private static void checkOne(String label, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(HUNDRED) > 0) {
            throw new BusinessException(label + "必须在 0~100 之间（当前 " + value.toPlainString() + "）");
        }
    }

    /** 总分 = 平时成绩*0.4 + 考试成绩*0.6 */
    private BigDecimal calcTotal(Score score) {
        BigDecimal usual = score.getUsualScore() != null ? score.getUsualScore() : BigDecimal.ZERO;
        BigDecimal exam = score.getExamScore() != null ? score.getExamScore() : BigDecimal.ZERO;
        return usual.multiply(USUAL_WEIGHT)
                .add(exam.multiply(EXAM_WEIGHT))
                .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 判定是否通过（含补考），规则委托 {@link GpaService#isPassed}。
     *
     * <p>刻意不在此重复实现"60 分及格/补考通过记 60"的逻辑 —— 规则只有一处实现，
     * 否则会出现"绩点算通过了、passed 标记没通过"的不一致。
     */
    private Integer calcPassed(BigDecimal totalScore, BigDecimal makeupScore) {
        return gpaService.isPassed(totalScore, makeupScore) ? 1 : 0;
    }
}
