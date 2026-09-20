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
}
