package duyell.ai.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import utils.JwtUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 端点在"客户端用错传输/方法"时的**HTTP 状态码**测试（2026-09-28 由 Cursor 连不上引出）。
 *
 * <h3>它挡的是什么</h3>
 * <p>{@code /mcp/sse} 只支持 {@code GET}（SSE 长连接）。而 MCP 客户端（Cursor / Claude Desktop）
 * 普遍**先试 streamable HTTP**（往同一 URL POST 一条 JSON-RPC），拿到 4xx 才回退到老式 SSE
 * ——这是协议里写明的回退路径。此前这里没有匹配的处理器，异常一路落到
 * {@code GlobalExceptionHandler#handleException(Exception)}，于是回的是
 * <b>HTTP 200 + {@code {"code":"500","msg":"服务器内部错误"}}</b>。
 *
 * <p>后果有两层，第二层才是真问题：
 * <ol>
 *   <li>现象极难查：客户端只说 {@code connection:connect_failure}，
 *       而它拿到的那坨 JSON 在它眼里是"JSON-RPC 响应解析失败"
 *       （Cursor 的日志原文：{@code Transient error connecting to streamableHttp server:
 *       Unrecognized keys: "code", "msg", "data"}）；</li>
 *   <li><b>回退路径被掐断</b>：回 5xx 等于告诉客户端"服务器坏了"，它就不会再试 SSE。</li>
 * </ol>
 *
 * <h3>为什么这条断言写成"4xx 且不是 500 信封"</h3>
 * <p>不写死 404：契约是"**4xx 才算用错端点的信号**"（协议回退靠它触发），
 * 具体是 404 还是 405 属于框架细节——当前实测是 404（静态资源处理器抛
 * {@code NoResourceFoundException}）。写死具体码只会让测试在框架升级时无端变红。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ai.mcp.enabled=true")
class McpEndpointHttpStatusTest {

    /** 用另一个学生账号：避免把 2023001 的 Redis 令牌顶掉（本机调试 Cursor 时正在用那个） */
    private static final String USERNAME = "2023002";
    private static final String ROLE = "student";
    private static final String REDIS_KEY = "token:" + USERNAME;

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 拦截器按"Redis 里的最新 token"校验，所以测试里也要把令牌放进去（等价于一次登录） */
    private String loginLikeToken() {
        String token = jwtUtil.generateToken(USERNAME, ROLE);
        redisTemplate.opsForValue().set(REDIS_KEY, token);
        return token;
    }

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(REDIS_KEY);
    }

    @Test
    void postToTheSseEndpointIsRejectedWith4xxNotAHundredEnvelope() throws Exception {
        HttpResponse<String> response = post("/mcp/sse", loginLikeToken());

        int status = response.statusCode();
        String body = response.body();

        assertTrue(status >= 400 && status < 500,
                "MCP 客户端会先往 /mcp/sse 试 streamable HTTP，并靠 **4xx** 回退到 SSE；"
                        + "回 5xx 等于告诉它'服务器坏了'，回退不会发生。实际状态码=" + status + "，体=" + body);
        assertFalse(body.contains("\"code\":\"500\""),
                "又回到了那个 500 信封（客户端会把它当 JSON-RPC 响应解析失败，症状变成一句 connect_failure）。体=" + body);
        assertTrue(body.contains("\"code\":\"" + status + "\""),
                "响应体里的 code 应与 HTTP 状态码一致，否则同一个错误有两个说法。状态码=" + status + "，体=" + body);
    }

    /** 没有令牌时应当是 401（鉴权先于"端点不存在"）——顺序本身也是契约的一部分 */
    @Test
    void postWithoutTokenIsStill401() throws Exception {
        HttpResponse<String> response = post("/mcp/sse", null);
        assertEquals(401, response.statusCode(),
                "未认证请求应当先被拦截器拒掉；实际=" + response.statusCode() + "，体=" + response.body());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"));
        if (token != null) {
            builder.header("token", token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
