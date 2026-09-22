package duyell.service.impl;

import com.duyell.Course;
import com.duyell.TeacherEvaluation;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.mapper.EvaluationMapper;
import duyell.service.EvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import utils.BusinessException;
import utils.PageResult;

import java.util.List;

/**
 * {@link EvaluationService} 实现。
 *
 * <p>制度依据：{@code docs/policies/08-教学评价规则.md}。
 * 2026-09-22 按作者确认补了两条规则（原先都**只有界面约束、服务端不拦**）：
 * <ol>
 *   <li><b>匿名</b>：教师侧读取一律剥掉提交人身份；</li>
 *   <li><b>归属校验</b>：只能评价本人已选课程的授课教师，评价对象由**课程**推导。</li>
 * </ol>
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationServiceImpl implements EvaluationService {

    private final EvaluationMapper evaluationMapper;
    private final CourseMapper courseMapper;
    private final CourseSelectionMapper courseSelectionMapper;

    @Override
    public void add(TeacherEvaluation evaluation) {
        if (evaluation == null || evaluation.getCourseId() == null) {
            throw new BusinessException("缺少课程信息，无法提交评价");
        }
        String studentId = evaluation.getStudentId();
        if (studentId == null || studentId.isBlank()) {
            throw new BusinessException("缺少学生信息，无法提交评价");
        }

        Course course = courseMapper.selectCourseById(evaluation.getCourseId());
        if (course == null) {
            throw new BusinessException("课程不存在，无法评价");
        }

        // ① 只能评价本人**已选**课程（原实现只靠界面约束，服务端不拦）
        if (courseSelectionMapper.select(evaluation.getCourseId(), studentId) == null) {
            throw new BusinessException("你还没有选这门课，不能评价该课程的教师");
        }

        // ② 评价对象由**课程**决定，不接受调用方传入的 teacherId
        //    （否则可以把某门课的评价挂到别的教师名下）
        String courseTeacherId = course.getTeacherId();
        if (courseTeacherId == null || courseTeacherId.isBlank()) {
            throw new BusinessException("该课程尚未安排授课教师，无法评价");
        }
        if (evaluation.getTeacherId() != null && !evaluation.getTeacherId().isBlank()
                && !courseTeacherId.equals(evaluation.getTeacherId())) {
            log.info("评价对象与课程授课教师不一致，已按课程纠正: course={}, 传入={}, 实际={}",
                    evaluation.getCourseId(), evaluation.getTeacherId(), courseTeacherId);
        }
        evaluation.setTeacherId(courseTeacherId);

        // ③ 每门课每生只能评价一次：数据库唯一键兜底，这里先给出可读原因（否则重复提交会变成 500）
        TeacherEvaluation existing = evaluationMapper.selectByCourseAndStudent(
                evaluation.getCourseId(), studentId);
        if (existing != null) {
            throw new BusinessException("你已经评价过这门课程，不能重复提交");
        }

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
    public List<TeacherEvaluation> list(Integer courseId, String studentId, String teacherId) {
        return evaluationMapper.list(courseId, studentId, teacherId);
    }

    @Override
    public PageResult<TeacherEvaluation> pageForTeacher(Integer pageNum, Integer pageSize, String teacherId) {
        PageResult<TeacherEvaluation> result = page(pageNum, pageSize, null, null, teacherId);
        if (result.getList() != null) {
            result.getList().forEach(EvaluationServiceImpl::stripStudentIdentity);
        }
        return result;
    }

    @Override
    public List<TeacherEvaluation> listForTeacher(String teacherId) {
        List<TeacherEvaluation> rows = evaluationMapper.list(null, null, teacherId);
        if (rows != null) {
            rows.forEach(EvaluationServiceImpl::stripStudentIdentity);
        }
        return rows;
    }

    /**
     * 匿名化：把"谁提交的"抹掉。
     *
     * <p>刻意做成一个**显式命名的单点**：教师侧任何新入口都必须调用它，
     * 而不是各自记得去 setStudentId(null)（那样迟早漏一处，匿名就破了）。
     */
    private static void stripStudentIdentity(TeacherEvaluation evaluation) {
        evaluation.setStudentId(null);
        evaluation.setStudentName(null);
    }

    @Override
    public TeacherEvaluation check(Integer courseId, String studentId) {
        return evaluationMapper.selectByCourseAndStudent(courseId, studentId);
    }

    @Override
    public Double avgScoreByTeacherId(String teacherId) {
        return evaluationMapper.avgScoreByTeacherId(teacherId);
    }
}
