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
     * @param pageNum 页码
     * @param pageSize 页大小
     * @param role 角色
     * @param username 用户名
     * @return 用户列表
     */
    public PageResult<SysUser> page(Integer pageNum, Integer pageSize, String role, String username);

    /**
     * 添加用户
     * @param sysUser 用户
     */
    void add(SysUser sysUser);

    /**
     * 删除用户
     * @param id 用户id
     */
    void delete(Integer id);

    /**
     * 修改用户
     * @param sysUser 用户
     */
    void update(SysUser sysUser);

    /**
     * 修改用户状态
     * @param id 用户id
     * @param status 状态
     */
    void updateStatus(Integer id, Integer status);
}
