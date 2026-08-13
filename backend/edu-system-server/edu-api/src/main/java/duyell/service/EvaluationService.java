package duyell.service;

import com.duyell.TeacherEvaluation;
import utils.PageResult;

public interface EvaluationService {

    void add(TeacherEvaluation evaluation);

    PageResult<TeacherEvaluation> page(Integer pageNum, Integer pageSize,
                                        Integer courseId, String studentId, String teacherId);

    TeacherEvaluation check(Integer courseId, String studentId);

    /**
     * 教师收到的所有评价的平均分（不受分页影响）
     * @param teacherId 教师工号
     * @return 平均分
     */
    Double avgScoreByTeacherId(String teacherId);
}
