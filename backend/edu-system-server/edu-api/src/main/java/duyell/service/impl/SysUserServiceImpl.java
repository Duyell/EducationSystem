package duyell.service.impl;

import com.duyell.SysUser;
import duyell.mapper.SysUserMapper;
import duyell.service.SysUserService;
import jakarta.servlet.http.HttpServletRequest;
import login.LoginReqDTO;
import login.LoginRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import utils.JwtUtil;

import java.util.concurrent.TimeUnit;

/**
 * @author duyell
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();


    @Override
    public LoginRespDTO login(LoginReqDTO loginReqDTO) {
        // 1. 根据用户名查用户
        SysUser user = sysUserMapper.selectByUsername(loginReqDTO.getUsername());
        if (user == null) {
            throw new RuntimeException("用户名不存在");
        }

        // 2. 状态校验
        if (user.getStatus() == 0) {
            throw new RuntimeException("账号已禁用");
        }

        // 3. BCrypt 密码校验
        if (!passwordEncoder.matches(loginReqDTO.getPassword(), user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // 4. 生成 token
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        // 5. 存入 redis
        redisTemplate.opsForValue().set("token:" + user.getUsername(), token, 7, TimeUnit.DAYS);

        // 6. 返回
        LoginRespDTO resp = new LoginRespDTO();
        resp.setToken(token);
        resp.setUsername(user.getUsername());
        resp.setRole(user.getRole());
        return resp;
    }

    @Override
    public void logout(HttpServletRequest request) {
        // 1. 从请求头获取 token
        String token = request.getHeader("token");

        if (StringUtils.isEmpty(token)) {
            throw new RuntimeException("未登录，无法登出");
        }

        try {
            // 2. 解析 token，拿到用户名
            String username = jwtUtil.getUsernameFromToken(token);

            // 3. 删除 Redis 里的 token
            String redisKey = "token:" + username;
            redisTemplate.delete(redisKey);

        } catch (Exception e) {
            throw new RuntimeException("token 无效，登出失败");
        }
    }


}
