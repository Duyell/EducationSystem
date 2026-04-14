package duyell.mapper;

import com.duyell.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * @author duyell
 */
@Mapper
public interface SysUserMapper {
    /**
     * 根据用户名（学号、工号）查找用户
     * @param username 用户名
     * @return 用户对象
     */
    @Select("select * from sys_user where username = #{username}")
    SysUser selectByUsername(String username);

    /**
     * 根据教师id查找教师名称
     * @param teacherId 教师id
     * @return 教师名称
     */
    @Select("select teacher_name from teacher where teacher_id = #{teacherId}")
    String selectByTeacherid(String teacherId);

    /**
     * 根据学生id查找学生名称
     * @param studentId 学生id
     * @return 学生名称
     */
    @Select("select student_name from student where student_id = #{studentId}")
    String selectByStudentid(String studentId);

    /**
     * 统计学生数量
     * @return 学生数量
     */
    @Select("select count(*) from sys_user where role = 'student'")
    int countStudent();

    /**
     * 统计教师数量
     * @return 教师数量
     */
    @Select("select count(*) from sys_user where role = 'teacher'")
    int countTeacher();
}
