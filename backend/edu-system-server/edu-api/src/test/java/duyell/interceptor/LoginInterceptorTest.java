// 包名放在 duyell.* 下：@SpringBootTest 需要从测试类所在包向上找到 @SpringBootConfiguration，
// 而拦截器本体在 interceptor 包（不在 duyell 之下），测试留在那里会直接报
// "Unable to find a @SpringBootConfiguration"（已实测）。
package duyell.interceptor;

import interceptor.LoginInterceptor;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import utils.JwtUtil;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录/鉴权拦截器测试（真实 Redis）。
 *
 * <p>这个类此前**完全没有测试**，而它是全部接口的唯一入口守卫：规则顺序、Redis 比对、
 * 异步派发行为都只靠手测。补上它是因为一个真实事故（见
 * {@link #asyncDispatchIsNotReAuthenticated()} 的注释）。
 *
 * <p>每个用例用随机用户名，并在结束后清掉自己写的 {@code token:} key——
 * 共用用户名会互相覆盖 token，这正是审计/限流测试踩过的同一类坑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LoginInterceptorTest {

    @Autowired
    private LoginInterceptor interceptor;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String username;

    private String tokenFor(String role) {
        username = "it-" + UUID.randomUUID();
        String token = jwtUtil.generateToken(username, role);
        redisTemplate.opsForValue().set("token:" + username, token, Duration.ofMinutes(5));
        return token;
    }

    @AfterEach
    void cleanUp() {
        if (username != null) {
            redisTemplate.delete("token:" + username);
        }
    }

    private static MockHttpServletRequest request(String method, String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader("token", token);
        }
        return request;
    }

    @Test
    void validTokenPassesAndExposesContext() throws Exception {
        String token = tokenFor("student");
        MockHttpServletRequest request = request("GET", "/gpa/my", token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed, "有效 token 应放行");
        assertEquals(username, request.getAttribute("username"), "controller 依赖的 username 必须被写入");
        assertEquals("student", request.getAttribute("role"));
    }

    @Test
    void missingTokenIsRejectedWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("GET", "/gpa/my", null), response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("未登录"), response.getContentAsString());
    }

    /**
     * ⚠️ 回归测试：异步派发阶段**不得**重复鉴权。
     *
     * <p>真实事故：AI 对话的 SSE 流正在进行时，同一用户在别处登录（或任何重新登录），
     * Redis 里的 {@code token:<username>} 被新 token 覆盖；随后 SSE 完成触发的
     * **ASYNC 派发**会再经过一遍拦截器，于是这个**早已鉴权通过、且响应已提交**的请求
     * 被判"登录失效"，在 reject() 里写 JSON 时抛出
     * {@code IllegalStateException: getOutputStream() has already been called}，
     * 客户端看到的是流被掐断（{@code TypeError: terminated}）+ 一个 500。
     *
     * <p>所以：ASYNC 派发直接放行，连 token 都不该看。
     */
    @Test
    void asyncDispatchIsNotReAuthenticated() throws Exception {
        // 故意不带 token、也不在 Redis 里放任何东西：只要是 ASYNC 派发就必须放行
        MockHttpServletRequest request = request("POST", "/ai/chat", null);
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed, "异步派发不应重新鉴权（否则会掐断正在输出的 SSE 流）");
        assertEquals(200, response.getStatus(), "放行就不该写任何错误响应");
    }

    /**
     * 响应已提交时不能再写拒绝体：SSE 流已经开始输出时写 JSON 会抛
     * {@code IllegalStateException}，把干净的拒绝变成 500 + 一屏堆栈。
     */
    @Test
    void rejectOnCommittedResponseDoesNotThrow() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        boolean allowed = interceptor.preHandle(request("GET", "/gpa/my", null), response, new Object());

        assertFalse(allowed, "仍然要拒绝");
        assertEquals(200, response.getStatus(), "已提交的响应不应被改动");
    }

    @Test
    void staleTokenIsRejected() throws Exception {
        String token = tokenFor("student");
        // 模拟"在别处重新登录"：Redis 里换成另一个 token
        redisTemplate.opsForValue().set("token:" + username, "someone-elses-token", Duration.ofMinutes(5));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("GET", "/gpa/my", token), response, new Object());

        assertFalse(allowed, "与 Redis 中的最新 token 不一致应拒绝（旧 token 立即失效）");
        assertEquals(401, response.getStatus());
    }

    @Test
    void roleRulesAreEnforced() throws Exception {
        // 学生访问仅管理员的 /training-plan（规则命中 /training-plan/**）
        String studentToken = tokenFor("student");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "/training-plan", studentToken), denied, new Object()),
                "学生不应能访问仅管理员的接口");
        assertEquals(403, denied.getStatus());

        // 但同一角色访问放行的子路径必须通过（/gpa/my 这类"先匹配先赢"的规则）
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request("GET", "/gpa/my", studentToken), allowed, new Object()),
                "学生应能访问自己的绩点接口");
    }

    @Test
    void invalidTokenIsRejectedBeforeRedisLookup() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("GET", "/gpa/my", "not-a-jwt"), response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertNotNull(response.getContentAsString());
    }
}
