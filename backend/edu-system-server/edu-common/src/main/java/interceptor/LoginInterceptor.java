package interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import utils.JwtUtil;

/**
 * @author duyell
 * 拦截器：检查用户是否登录
 */
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            response.setStatus(401);
            return false;
        }

        if (jwtUtil.isTokenExpired(token)) {
            response.setStatus(401);
            return false;
        }
        String username = jwtUtil.getUsernameFromToken(token);
        String redisToken = redisTemplate.opsForValue().get("token:" + username);
        if (redisToken == null || redisToken.isEmpty()) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
