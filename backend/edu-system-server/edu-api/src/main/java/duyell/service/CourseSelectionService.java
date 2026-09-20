package duyell.service;

import com.duyell.Course;

import java.util.List;

/**
 * 选课业务：事务内完成六道校验与并发控制。
 *
 * <p><b>六道校验链</b>（docs/教务业务扩展设计.md §5 P3，顺序即业务含义）：
 * <pre>
 * 1. 轮次开启      —— 管理员没开开关就不能选（否则只能看）
 * 2. 适用范围      —— 年级/专业/学院是否在轮次范围内
 * 3. 学分上限      —— 本轮已选学分 + 本课学分 不超过 max_credits
 * 4. 未修过        —— 同一 course_code 已通过的课不再重选
 * 5. 无时间冲突    —— 与已有课表逐对比较（复用 P2 的闭区间重叠判据）
 * 6. 容量未满      —— 选课人数 < max_student
 * </pre>
 * 第 1、2 道由 {@link SelectionRoundService} 判定（它同时给出命中的轮次）。
 *
 * <p>⚠️ 校验链只有**一处实现**（{@code CourseSelectionServiceImpl#selectionBlocker}）：
 * 选课提交和"可选课程列表"都走它，避免出现"列表说能选、提交却说不能"。
 *
 * @author duyell
 */
public interface CourseSelectionService {

    /**
     * 选课（事务内：锁定课程行 → 六道校验 → 插入）
     * @param courseId  课程id
     * @param studentId 学生学号
     */
    void select(Integer courseId, String studentId);

    /**
     * 退课（只能在选课期间或补退选期间退）
     * @param courseId  课程id
     * @param studentId 学生学号
     */
    void drop(Integer courseId, String studentId);

    /**
     * 我的已选课程列表
     * @param studentId 学生学号
     * @return 课程列表
     */
    List<Course> listMyCourses(String studentId);

    /**
     * 我的已选课程ID列表
     * @param studentId 学生学号
     * @return 课程ID列表
     */
    List<Integer> listMyCourseIds(String studentId);

    /**
     * 某学期的可选课程（学生选课页）。
     *
     * @param selected   本人是否已选
     * @param selectable 现在能不能选（false 时 reason 说明原因，可直接展示）
     */
    record SelectableCourse(Course course,
                            boolean selected,
                            boolean selectable,
                            String reason) {
    }

    List<SelectableCourse> listSelectableCourses(String studentId, String term);
}
