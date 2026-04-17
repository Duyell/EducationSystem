package duyell.mapper;

import com.duyell.SysUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
    String selectByStudentId(String studentId);

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

    /**
     * 分页查询
     * @return 查询列表
     */
    @Select("select * from sys_user")
    public List<SysUser> list();

    /**
     * 添加用户
     * @param sysUser 用户对象
     */
    @Insert("insert into sys_user(username,password,email,phone,role) values(#{username},#{password},#{email},#{phone},#{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(SysUser sysUser);

    /**
     * 批量删除用户
     * @param ids 用户id的集合
     */
    void deleteByIds(List<String> ids);

    /**
     * 更新用户
     * @param sysUser 用户对象
     */
    void update(SysUser sysUser);

    /**
     * 统计用户数量
     * @return 用户数量
     */
    @Select("select count(*) from sys_user")
    int countSysUser();
}
