package duyell.service;

import com.duyell.Course;

import java.util.List;

/**
 * @author duyell
 * 选课业务：事务内完成查重、容量校验与并发控制
 */
public interface CourseSelectionService {

    /**
     * 选课（事务内：锁定课程行 → 查重 → 容量校验 → 插入）
     * @param courseId  课程id
     * @param studentId 学生学号
     */
    void select(Integer courseId, String studentId);

    /**
     * 退课
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
}
