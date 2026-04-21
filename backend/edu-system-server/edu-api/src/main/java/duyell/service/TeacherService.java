package duyell.service;

import com.duyell.Teacher;
import utils.PageResult;

/**
 * @author duyell
 */
public interface TeacherService {
    /**
     * 获取教师列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param teacherName 教师名称
     * @param teacherId 教师id
     * @param collegeId 学院id
     * @param title 职称
     * @return 教师列表
     */
    PageResult<Teacher> page(Integer pageNum, Integer pageSize, String teacherName, String teacherId, Integer collegeId, String title);

    /**
     * 添加教师
     * @param teacher 教师
     */
    void add(Teacher teacher);

    /**
     * 删除教师
     * @param teacherId 教师id
     */
    void delete(Integer teacherId);

    /**
     * 修改教师信息
     * @param teacher 教师
     */
    void update(Teacher teacher);
}
