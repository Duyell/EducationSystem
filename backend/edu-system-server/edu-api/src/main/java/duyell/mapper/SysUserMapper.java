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
}
