package duyell.service.impl;

import com.duyell.Score;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.ScoreMapper;
import duyell.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@Service
public class ScoreServiceImpl implements ScoreService {
    private final ScoreMapper scoreMapper;

    @Override
    public PageResult<Score> page(Integer pageNum, Integer pageSize, Integer studentId, Integer courseId,String term) {
        Page<Score> pageResult=  PageHelper.startPage(pageNum, pageSize);
        List<Score> scoreList = scoreMapper.list(courseId, studentId, term);
        return new PageResult<>(pageResult.getTotal(), scoreList);
    }

    @Override
    public void add(Score score) {
        scoreMapper.add(score);
    }

    @Override
    public void delete(Integer id) {
        scoreMapper.deleteByIds(List.of(id));
    }

    @Override
    public void update(Score score) {
        scoreMapper.update(score);
    }
}
