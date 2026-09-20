package duyell.service;

import com.duyell.PlanCourse;
import com.duyell.Student;
import com.duyell.TrainingPlan;

import java.util.List;

/**
 * 培养计划服务。
 *
 * <p>见 {@code docs/教务业务扩展设计.md} §3.2(1)(2)、§4.3。
 *
 * @author duyell
 */
public interface TrainingPlanService {

    /**
     * 定位某学生适用的培养计划。
     *
     * <p>版本策略（设计文档 §2.1）：学生沿用其**入学年级**对应的方案（老生不改，后续年级可换新版）。
     * 由 {@code student → clazz.grade} + {@code clazz.major_id} 推导。
     *
     * @param studentId 学号
     * @return 适用方案；学生不存在或无匹配方案时返回 null
     */
    TrainingPlan resolvePlanForStudent(String studentId);

    /**
     * 查某方案的全部课程明细（含必修与选修，按类别与建议学期排序）。
     *
     * @param planId 方案 id
     * @return 课程明细列表
     */
    List<PlanCourse> listPlanCourses(Integer planId);

    /**
     * 查某方案指定类别的课程明细。
     *
     * @param planId   方案 id
     * @param category {@link PlanCourse#CATEGORY_REQUIRED} 或 {@link PlanCourse#CATEGORY_ELECTIVE}
     */
    List<PlanCourse> listPlanCourses(Integer planId, String category);

    /**
     * 查学生的定位信息（年级、专业）。用于展示"我的方案"时说明依据。
     *
     * @param studentId 学号
     * @return 含 grade / majorId / majorName / collegeName 的学生对象；不存在返回 null
     */
    Student getStudentPlacement(String studentId);

    // ---------- 管理员维护 ----------

    /**
     * 培养计划列表（可按专业/年级筛选）。
     */
    List<TrainingPlan> listPlans(Integer majorId, String grade);

    /** 按 id 查方案；不存在返回 null */
    TrainingPlan getPlan(Integer planId);

    /**
     * 新建培养计划。
     *
     * @throws utils.BusinessException 同专业同年级已存在启用中的方案时
     */
    Integer createPlan(TrainingPlan plan);

    /** 更新培养计划（按 id） */
    void updatePlan(TrainingPlan plan);

    /**
     * 向方案添加课程明细。
     *
     * @throws utils.BusinessException 课程代码为空、或该方案中已存在同一课程代码时
     */
    void addPlanCourse(PlanCourse planCourse);

    /** 从方案移除课程明细（按明细 id） */
    void removePlanCourse(Integer planCourseId);
}
