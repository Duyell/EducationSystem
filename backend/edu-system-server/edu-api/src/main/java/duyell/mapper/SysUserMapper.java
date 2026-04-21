package duyell.mapper;

import com.duyell.SysUser;
import org.apache.ibatis.annotations.*;

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
     * @param role 角色
     * @param username 用户名
     * @return 查询列表
     */
    public List<SysUser> list(@Param("role") String role,@Param("username") String username);

    /**
     * 添加用户
     * @param sysUser 用户对象
     */
    @Insert("insert into sys_user(username,password,email,phone,role) values(#{username},#{password},#{email},#{phone},#{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(SysUser sysUser);

    /**
     * 批量删除用户
     * @param ids 用户学号工号的集合
     */
    void deleteByIds(List<Integer> ids);

    /**
     * 批量删除用户
     * @param usernames 用户学号工号的集合
     */
    void deleteByUsernames(List<String> usernames);

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

    /**
     * 统计课程数量
     * @return 课程数量
     */
    @Select("select count(DISTINCT course_name) from course")
    int countCourse();

    /**
     * 设置用户状态
     * @param id 用户id
     * @param status 用户状态
     */
    @Update("update sys_user set status = #{status} where id = #{id} ")
    void updateStatus(Integer id,Integer status);

    /**
     * 查询班级总数
     * @return 班级总数
     */
    @Select("select count(*) from clazz")
    int countClazz();

    /**
     * 根据id查询用户
     * @param id 用户id
     * @return 用户
     */
    @Select("select * from sys_user where id = #{id} ")
    SysUser selectById(Integer id);
}
