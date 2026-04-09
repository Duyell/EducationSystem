package duyell.service;

import jakarta.servlet.http.HttpServletRequest;
import login.LoginReqDTO;
import login.LoginRespDTO;

/**
 * @author duyell
 * 用户服务
 */
public interface SysUserService {
    /**
     * 登录
     * @param loginReqDTO 登录参数
     * @return 登录结果
     */
    LoginRespDTO login(LoginReqDTO loginReqDTO);

    /**
     * 登出
     * @param request 请求
     */
    void logout(HttpServletRequest request);
}
