package duyell.service;

import com.duyell.TeacherEvaluation;
import utils.PageResult;

public interface EvaluationService {

    void add(TeacherEvaluation evaluation);

    PageResult<TeacherEvaluation> page(Integer pageNum, Integer pageSize,
                                        Integer courseId, String studentId, String teacherId);

    TeacherEvaluation check(Integer courseId, String studentId);
}
