package duyell.service;

import com.duyell.SysUser;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
public interface SysUserService {
    /**
     * 获取用户列表
     * @return 用户列表
     */
    public PageResult<SysUser> page(Integer pageNum, Integer pageSize);

    /**
     * 添加用户
     * @param sysUser 用户
     */
    void add(SysUser sysUser);

    /**
     * 删除用户
     * @param username 用户名(工号或者学号)
     */
    void delete(String username);

    /**
     * 修改用户
     * @param sysUser 用户
     */
    void update(SysUser sysUser);
}
