package duyell.service.impl;

import com.duyell.PlanCourse;
import com.duyell.Student;
import com.duyell.TrainingPlan;
import duyell.mapper.PlanCourseMapper;
import duyell.mapper.StudentMapper;
import duyell.mapper.TrainingPlanMapper;
import duyell.service.TrainingPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link TrainingPlanService} 实现。
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingPlanServiceImpl implements TrainingPlanService {

    private final StudentMapper studentMapper;
    private final TrainingPlanMapper trainingPlanMapper;
    private final PlanCourseMapper planCourseMapper;

    @Override
    public TrainingPlan resolvePlanForStudent(String studentId) {
        Student placement = getStudentPlacement(studentId);
        if (placement == null) {
            log.info("学生不存在，无法定位培养计划: studentId={}", studentId);
            return null;
        }
        if (placement.getMajorId() == null || placement.getGrade() == null || placement.getGrade().isBlank()) {
            log.info("学生缺少专业或年级信息，无法定位培养计划: studentId={}, majorId={}, grade={}",
                    studentId, placement.getMajorId(), placement.getGrade());
            return null;
        }
        TrainingPlan plan = trainingPlanMapper.selectByMajorAndGrade(
                placement.getMajorId(), placement.getGrade());
        if (plan == null) {
            log.info("该专业年级暂无启用中的培养计划: majorId={}, grade={}",
                    placement.getMajorId(), placement.getGrade());
        }
        return plan;
    }

    @Override
    public List<PlanCourse> listPlanCourses(Integer planId) {
        if (planId == null) {
            return List.of();
        }
        return planCourseMapper.listByPlanId(planId);
    }

    @Override
    public List<PlanCourse> listPlanCourses(Integer planId, String category) {
        if (planId == null || category == null) {
            return List.of();
        }
        return planCourseMapper.listByPlanIdAndCategory(planId, category);
    }

    @Override
    public Student getStudentPlacement(String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return null;
        }
        return studentMapper.selectWithGradeAndMajor(studentId);
    }
}
