package interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import utils.JwtUtil;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * @author duyell
 * 拦截器：登录校验（JWT + Redis 比对）+ 角色权限校验
 * 权限规则从上到下依次匹配，命中第一条即生效；未命中任何规则的接口默认放行（仅要求登录）。
 */
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    private static final String ADMIN = "admin";
    private static final String TEACHER = "teacher";
    private static final String STUDENT = "student";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 权限规则：pattern + 方法集合（null=全部方法）+ 允许的角色 */
    private static final List<AuthRule> RULES = List.of(
            // 管理模块：整块仅管理员
            new AuthRule("/user/**", null, ADMIN),
            new AuthRule("/teacher/**", null, ADMIN),
            new AuthRule("/student/**", null, ADMIN),
            new AuthRule("/clazz/**", null, ADMIN),
            new AuthRule("/college/**", null, ADMIN),
            new AuthRule("/major/**", null, ADMIN),
            // 课程模块
            new AuthRule("/course/my", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/course/*/students", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/course/**", Set.of("POST", "PUT", "DELETE"), ADMIN),
            // 成绩模块
            new AuthRule("/score/my", Set.of("GET"), ADMIN, STUDENT),
            new AuthRule("/score/**", null, ADMIN, TEACHER),
            // 选课模块：仅学生
            new AuthRule("/course-selection/**", null, ADMIN, STUDENT),
            // 教评模块
            new AuthRule("/evaluate/teacher/**", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/evaluate/**", null, ADMIN, STUDENT)
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // CORS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // ================= 1. 登录校验 =================
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            return reject(response, 401, "未登录");
        }

        String username;
        String role;
        try {
            if (jwtUtil.isTokenExpired(token)) {
                return reject(response, 401, "登录已过期，请重新登录");
            }
            username = jwtUtil.getUsernameFromToken(token);
            role = jwtUtil.getRoleFromToken(token);
        } catch (Exception e) {
            return reject(response, 401, "token 无效");
        }

        // Redis 比对：与当前最新 token 一致才放行（重新登录后旧 token 立即失效）
        String redisToken = redisTemplate.opsForValue().get("token:" + username);
        if (redisToken == null || redisToken.isEmpty() || !redisToken.equals(token)) {
            return reject(response, 401, "登录已失效，请重新登录");
        }

        // ================= 2. 角色鉴权 =================
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        for (AuthRule rule : RULES) {
            if (PATH_MATCHER.match(rule.pattern(), path)
                    && (rule.methods() == null || rule.methods().contains(method))) {
                if (role == null || !rule.roles().contains(role)) {
                    return reject(response, 403, "无权限访问该接口");
                }
                break; // 命中规则且角色通过
            }
        }

        // ================= 3. 供 Controller 使用的上下文 =================
        request.setAttribute("username", username);
        request.setAttribute("role", role);
        return true;
    }

    /** 返回 JSON 错误响应 */
    private boolean reject(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + status + "\",\"msg\":\"" + msg + "\",\"data\":null}");
        return false;
    }

    /** 权限规则定义 */
    private record AuthRule(String pattern, Set<String> methods, Set<String> roles) {
        AuthRule(String pattern, Set<String> methods, String... roles) {
            this(pattern, methods, Set.of(roles));
        }
    }
}
