package duyell.service.impl;

import com.duyell.TeacherEvaluation;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.EvaluationMapper;
import duyell.service.EvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import utils.PageResult;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EvaluationServiceImpl implements EvaluationService {

    private final EvaluationMapper evaluationMapper;

    @Override
    public void add(TeacherEvaluation evaluation) {
        evaluationMapper.add(evaluation);
    }

    @Override
    public PageResult<TeacherEvaluation> page(Integer pageNum, Integer pageSize,
                                               Integer courseId, String studentId, String teacherId) {
        Page<TeacherEvaluation> pageResult = PageHelper.startPage(pageNum, pageSize);
        List<TeacherEvaluation> list = evaluationMapper.list(courseId, studentId, teacherId);
        return new PageResult<>(pageResult.getTotal(), list);
    }

    @Override
    public TeacherEvaluation check(Integer courseId, String studentId) {
        return evaluationMapper.selectByCourseAndStudent(courseId, studentId);
    }
}
