package duyell.service;

import com.duyell.Student;
import utils.PageResult;

/**
 * @author duyell
 */
public interface StudentService {
    /**
     * 获取学生列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param studentName 学生姓名
     * @param studentId 学号
     * @param collegeId 学院id
     * @param majorId 专业id
     * @param clazzId 班级id
     * @return 学生列表
     */
    public PageResult<Student> page(Integer pageNum, Integer pageSize, String studentName, String studentId, Integer collegeId, Integer majorId, Integer clazzId);

    /**
     * 添加学生
     * @param student 学生
     */
    public void add(Student student);

    /**
     * 删除学生
     * @param studentId 学生id
     */
    public void delete(Integer studentId);

    /**
     * 修改学生信息
     * @param student 学生
     */
    public void update(Student student);
}
