package interceptor;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    private static final String ADMIN = "admin";
    private static final String TEACHER = "teacher";
    private static final String STUDENT = "student";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 权限规则：pattern + 方法集合（null=全部方法）+ 允许的角色。
     *
     * <p>⚠️ <b>顺序敏感</b>：自上而下匹配，命中第一条即生效。
     * 因此「某角色的专属子路径」必须排在「整块给另一角色」之前，
     * 例如 {@code /gpa/my} 必须在 {@code /gpa/**} 之前，否则学生会被后者拒掉。
     */
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
            new AuthRule("/evaluate/**", null, ADMIN, STUDENT),
            // 绩点模块：教师无此功能（用户明确要求）
            // 学生专属路径必须先于 /gpa/** 声明
            new AuthRule("/gpa/my", Set.of("GET"), ADMIN, STUDENT),
            new AuthRule("/gpa/rank", Set.of("GET"), ADMIN, STUDENT),
            new AuthRule("/gpa/audit/my", Set.of("GET"), ADMIN, STUDENT),
            new AuthRule("/gpa/rank/*", Set.of("GET"), ADMIN),
            new AuthRule("/gpa/audit/*", Set.of("GET"), ADMIN),
            new AuthRule("/gpa/**", null, ADMIN),
            // 培养计划见 TRAINING_PLAN_RULES（需在其前缀规则中放行学生的 /my）

            // ---------- 学业预警（JW-01 §5）----------
            // 学生看/确认**本人**预警（学号取自 token）；教师没有该功能。
            new AuthRule("/academic-warning/**", null, ADMIN, STUDENT),

            // ---------- P2：排课 / 教室 / 开课申请 ----------
            // ⚠️ 同样顺序敏感：越具体的路径必须排在 /xxx/** 之前，
            //    否则教师会被后面的 ADMIN 规则拒掉（或反过来越权放行）。
            // 教室：教师与管理员都能查"某时段空闲教室"（排课页要用）；其余教室维护仅管理员
            new AuthRule("/room/free", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/room/**", null, ADMIN),
            // 排课：课表查询与冲突检测对教师开放；全量课表、删除、审批仅管理员
            new AuthRule("/class-time/my", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/class-time/course/*", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/class-time/check", Set.of("POST"), ADMIN, TEACHER),
            new AuthRule("/class-time/apply/my", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/class-time/apply/*/approve", Set.of("POST"), ADMIN),
            new AuthRule("/class-time/apply/*/reject", Set.of("POST"), ADMIN),
            new AuthRule("/class-time/apply", Set.of("POST"), ADMIN, TEACHER),
            new AuthRule("/class-time/apply", Set.of("GET"), ADMIN),
            new AuthRule("/class-time/**", null, ADMIN),
            // 开课申请：教师提交/看自己的，管理员审批
            new AuthRule("/course-apply/my", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/course-apply/*/approve", Set.of("POST"), ADMIN),
            new AuthRule("/course-apply/*/reject", Set.of("POST"), ADMIN),
            new AuthRule("/course-apply", Set.of("POST"), ADMIN, TEACHER),
            new AuthRule("/course-apply", Set.of("GET"), ADMIN),
            // 详情：教师可查自己的；归属校验放在 Controller（要读到数据才知道属主）
            new AuthRule("/course-apply/*", Set.of("GET"), ADMIN, TEACHER),
            new AuthRule("/course-apply/**", null, ADMIN),
            // ---------- P3：选课轮次 ----------
            // ⚠️ /current 是学生要用的（"我现在能不能选/退"），必须排在 /selection-round/** 之前；
            //    写操作（建轮次、开关、范围、删除）只有管理员能做——
            //    用户明确："只有管理员开启选课后学生才能选"。
            new AuthRule("/selection-round/current", Set.of("GET"), ADMIN, STUDENT),
            new AuthRule("/selection-round/**", null, ADMIN),
            // ---------- P4：考试安排 ----------
            // 学生查"我的考试"；其余（排考、改、删）仅管理员。
            // ⚠️ /exam/my 必须排在 /exam/** 之前，否则学生被后者拒掉。
            new AuthRule("/exam/my", Set.of("GET"), ADMIN, STUDENT),
            new AuthRule("/exam/**", null, ADMIN)
    );

    /**
     * 培养计划：整块仅管理员，但要放行学生的 {@code /training-plan/my}。
     */
    private static final List<AuthRule> TRAINING_PLAN_RULES = List.of(
            new AuthRule("/training-plan/my", Set.of("GET"), ADMIN, STUDENT),
            new AuthRule("/training-plan/**", null, ADMIN)
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // CORS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 异步派发（Servlet 3 的 AsyncContext.dispatch）**不重复鉴权**。
        // 异步派发是同一个请求的第二次经过拦截器：它在 REQUEST 阶段已经鉴权通过，
        // 这里再查一次 Redis 不但多余，还会真的把流打断——用户换个标签页/设备重新登录后
        // `token:<username>` 被覆盖，于是**仍在进行中的** SSE 流（例如 AI 正在流式输出）
        // 会在派发阶段被判"登录失效"。更糟的是此时响应已提交，reject() 写不了 JSON，
        // 只会抛 IllegalStateException: getOutputStream() has already been called，
        // 表现为一个看不懂的 500 而不是 401。
        // （实测：并发登录同一用户会让正在流式输出的 /ai/chat 连接以 TypeError: terminated 断开。）
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
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
        // 培养计划规则先匹配：需要在其前缀规则中放行学生的 /training-plan/my
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        for (AuthRule rule : concat(RULES, TRAINING_PLAN_RULES)) {
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

    /** 按顺序拼接两组规则（前一组优先） */
    private static List<AuthRule> concat(List<AuthRule> a, List<AuthRule> b) {
        List<AuthRule> all = new java.util.ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    /** 返回 JSON 错误响应 */
    private boolean reject(HttpServletResponse response, int status, String msg) throws IOException {
        // 响应一旦提交就无法再改状态码或写响应体（典型场景：SSE 流已开始输出）。
        // 硬写会抛 IllegalStateException，把一次干净的拒绝变成 500 + 一屏堆栈，
        // 反而掩盖了真实原因（谁、因为什么被拒），所以这里只记日志、不动响应。
        if (response.isCommitted()) {
            log.warn("响应已提交，无法写入拒绝响应: status={}, msg={}", status, msg);
            return false;
        }
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
