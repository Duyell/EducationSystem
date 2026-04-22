package duyell.service;

import com.duyell.Course;
import utils.PageResult;

/**
 * @author duyell
 */
public interface CourseService {
    /**
     * 获取课程列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param courseName 课程名称
     * @param teacherName 教师名称
     * @param teacherId 教师id
     * @param collegeId 学院id
     * @param credit 学分
     * @param classHour 课时
     * @param maxStudent 最大人数
     * @return 课程列表
     */
    PageResult<Course> page(Integer pageNum, Integer pageSize, String courseName, String teacherName,String teacherId, Integer collegeId, Integer credit, Integer classHour, Integer maxStudent);

    /**
     * 添加课程
     * @param course 课程
     */
    void add(Course course);

    /**
     * 删除课程
     * @param courseId 课程id
     */
    void delete(Integer courseId);

    /**
     * 修改课程信息
     * @param course 课程
     */
    void update(Course course);
}
