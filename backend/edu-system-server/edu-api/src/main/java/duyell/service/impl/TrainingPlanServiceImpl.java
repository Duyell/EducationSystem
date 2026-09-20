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
import utils.BusinessException;

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

    // ---------- 管理员维护 ----------

    @Override
    public List<TrainingPlan> listPlans(Integer majorId, String grade) {
        return trainingPlanMapper.list(majorId, grade);
    }

    @Override
    public TrainingPlan getPlan(Integer planId) {
        return planId == null ? null : trainingPlanMapper.selectById(planId);
    }

    @Override
    public Integer createPlan(TrainingPlan plan) {
        requirePlanFields(plan);
        if (trainingPlanMapper.selectDuplicate(plan.getMajorId(), plan.getGrade(), -1) != null) {
            throw new BusinessException("该专业 " + plan.getGrade()
                    + " 级已存在培养计划，请先停用或删改原方案（同一专业年级只应有一个启用版本）");
        }
        if (plan.getStatus() == null) {
            plan.setStatus(1);
        }
        trainingPlanMapper.add(plan);
        log.info("新建培养计划: id={}, name={}, major={}, grade={}",
                plan.getId(), plan.getPlanName(), plan.getMajorId(), plan.getGrade());
        return plan.getId();
    }

    @Override
    public void updatePlan(TrainingPlan plan) {
        if (plan.getId() == null) {
            throw new BusinessException("缺少方案 id");
        }
        requirePlanFields(plan);
        // 改专业/年级时要重新查重，排除自身
        if (trainingPlanMapper.selectDuplicate(plan.getMajorId(), plan.getGrade(), plan.getId()) != null) {
            throw new BusinessException("该专业 " + plan.getGrade() + " 级已存在另一份培养计划");
        }
        trainingPlanMapper.update(plan);
    }

    @Override
    public void addPlanCourse(PlanCourse planCourse) {
        if (planCourse.getPlanId() == null) {
            throw new BusinessException("缺少方案 id");
        }
        if (planCourse.getCourseCode() == null || planCourse.getCourseCode().isBlank()) {
            throw new BusinessException("课程代码不能为空（培养计划以课程代码关联课程）");
        }
        if (!PlanCourse.CATEGORY_REQUIRED.equals(planCourse.getCategory())
                && !PlanCourse.CATEGORY_ELECTIVE.equals(planCourse.getCategory())) {
            throw new BusinessException("课程类别只能是 REQUIRED 或 ELECTIVE");
        }
        if (planCourseMapper.selectByPlanAndCode(planCourse.getPlanId(), planCourse.getCourseCode()) != null) {
            throw new BusinessException("该方案中已存在课程 " + planCourse.getCourseCode());
        }
        if (planCourse.getCredit() == null) {
            planCourse.setCredit(java.math.BigDecimal.ZERO);
        }
        planCourseMapper.add(planCourse);
    }

    @Override
    public void removePlanCourse(Integer planCourseId) {
        if (planCourseId == null) {
            throw new BusinessException("缺少明细 id");
        }
        planCourseMapper.deleteById(planCourseId);
    }

    private void requirePlanFields(TrainingPlan plan) {
        if (plan.getMajorId() == null) {
            throw new BusinessException("必须指定专业");
        }
        if (plan.getGrade() == null || plan.getGrade().isBlank()) {
            throw new BusinessException("必须指定适用年级");
        }
        if (plan.getPlanName() == null || plan.getPlanName().isBlank()) {
            throw new BusinessException("必须填写方案名称");
        }
        // 学分要求为空时按 0 处理，避免 NOT NULL 列报错
        if (plan.getTotalCredits() == null) {
            plan.setTotalCredits(java.math.BigDecimal.ZERO);
        }
        if (plan.getRequiredCredits() == null) {
            plan.setRequiredCredits(java.math.BigDecimal.ZERO);
        }
        if (plan.getElectiveCredits() == null) {
            plan.setElectiveCredits(java.math.BigDecimal.ZERO);
        }
    }
}
