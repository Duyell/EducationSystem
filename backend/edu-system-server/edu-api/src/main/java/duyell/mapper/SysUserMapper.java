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

    @Select("select teacher_name from teacher where teacher_id = #{teacherId}")
    String selectByTeacherid(String teacherId);

    @Select("select student_name from student where student_id = #{studentId}")
    String selectByStudentid(String studentId);
}
