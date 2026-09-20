package duyell.service.impl;

import com.duyell.Score;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.ScoreMapper;
import duyell.service.GpaService;
import duyell.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.BusinessException;
import utils.PageResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class ScoreServiceImpl implements ScoreService {
    private final ScoreMapper scoreMapper;
    private final GpaService gpaService;

    /** 平时成绩权重 */
    private static final BigDecimal USUAL_WEIGHT = BigDecimal.valueOf(0.4);
    /** 考试成绩权重 */
    private static final BigDecimal EXAM_WEIGHT = BigDecimal.valueOf(0.6);

    /** 成绩计算精度。与 score 表的 decimal(6,3) 保持一致（设计文档 §3.3：支持 75.76 这类分数） */
    private static final int SCORE_SCALE = 3;

    @Override
    public PageResult<Score> page(Integer pageNum, Integer pageSize, Integer studentId, Integer courseId, String term) {
        Page<Score> pageResult = PageHelper.startPage(pageNum, pageSize);
        List<Score> scoreList = scoreMapper.list(courseId, studentId, term);
        return new PageResult<>(pageResult.getTotal(), scoreList);
    }

    @Override
    public void add(Score score) {
        // 总分统一由服务端计算（平时*0.4 + 考试*0.6），不信任前端传入值
        score.setTotalScore(calcTotal(score));
        // passed 是派生字段，只在此处维护（设计文档 §3.3 风险 2）
        score.setPassed(calcPassed(score.getTotalScore(), score.getMakeupScore()));
        scoreMapper.add(score);
    }

    @Override
    public void delete(Integer id) {
        scoreMapper.deleteByIds(List.of(id));
    }

    @Override
    public void update(Score score) {
        // 只传单项分数时，取现有记录合并后再重算总分，避免另一项被清零
        if (score.getUsualScore() == null || score.getExamScore() == null
                || score.getMakeupScore() == null) {
            Score existing = scoreMapper.selectById(score.getId());
            if (existing == null) {
                throw new BusinessException("成绩记录不存在");
            }
            if (score.getUsualScore() == null) {
                score.setUsualScore(existing.getUsualScore());
            }
            if (score.getExamScore() == null) {
                score.setExamScore(existing.getExamScore());
            }
            if (score.getMakeupScore() == null) {
                score.setMakeupScore(existing.getMakeupScore());
            }
        }
        score.setTotalScore(calcTotal(score));
        score.setPassed(calcPassed(score.getTotalScore(), score.getMakeupScore()));
        scoreMapper.update(score);
    }

    @Override
    public Score selectById(Integer scoreId) {
        return scoreMapper.selectById(scoreId);
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
