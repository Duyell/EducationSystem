package duyell.ai.guard;

import com.duyell.AiConversation;
import com.duyell.AiMessage;
import com.duyell.AiToolAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.config.AiProperties;
import duyell.ai.dto.ChatMessage;
import duyell.ai.service.AiChatService;
import duyell.ai.service.OpenAiClient;
import duyell.mapper.AiMessageMapper;
import duyell.mapper.AiToolAuditMapper;
import duyell.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import utils.JwtUtil;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出护栏的**接线层**集成测试：用一个"坏模型"桩，必现地把工具调用写成正文，
 * 验证它真的被**恢复成真实调用并执行**（而不只是被过滤掉）。
 *
 * <p>为什么需要这一层：{@code ToolCallTextGuardTest} 只证明了"识别与扣留"这段纯逻辑；
 * 但"识别出来之后有没有接进工具调用流程、有没有过白名单与参数校验、有没有落审计"是**接线**问题，
 * 单测看不见。真模型上这个故障是**偶发**的（本项目 12 次实跑里只出现 1 次），
 * 靠反复跑真实模型来验证不可靠——所以这里用桩把它变成**确定性**用例。
 *
 * <p>桩模拟的正是实测抓到的形态：正文里出现
 * {@code {"name": "get_my_gpa", "arguments": {}}} 且**不调用** onToolCalls
 * （真实客户端只在模型走了工具调用通道时才回调 onToolCalls，见 {@code OpenAiClient}）。
 *
 * <p>桩的实现方式：{@code OpenAiClient} 是具体类且构造需要三个 Bean，直接**继承并覆盖
 * {@code streamChat}** 即可，不必引入 Mockito（本项目测试类路径上没有 Mockito）。
 *
 * <p>本测试会写真实审计表（后台线程无事务，必须是 {@code NOT_SUPPORTED}，与审计测试一致）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutputGuardrailIntegrationTest {

    private static final String STUDENT = "2023001";

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AiToolAuditMapper auditMapper;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AiMessageMapper messageMapper;

    /** 测试用的模型桩（@Primary 覆盖真实客户端），由下面的 {@link StubConfig} 注入 */
    @Autowired
    private StubOpenAiClient stubClient;

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubOpenAiClient stubOpenAiClient(HttpClient aiHttpClient, AiProperties aiProperties,
                                          ObjectMapper objectMapper) {
            return new StubOpenAiClient(aiHttpClient, aiProperties, objectMapper);
        }
    }

    /** "坏模型"桩：第一轮把工具调用写成正文，第二轮正常作答 */
    static class StubOpenAiClient extends OpenAiClient {

        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch modelFinished = new CountDownLatch(1);
        final List<List<ChatMessage>> historySeenByModel = new ArrayList<>();

        StubOpenAiClient(HttpClient httpClient, AiProperties aiProperties, ObjectMapper objectMapper) {
            super(httpClient, aiProperties, objectMapper);
        }

        @Override
        public void streamChat(List<ChatMessage> messages,
                               List<java.util.Map<String, Object>> tools,
                               Consumer<String> onToken,
                               Consumer<List<ChatMessage.ToolCall>> onToolCalls,
                               Consumer<String> onReasoningContent,
                               Runnable onDone,
                               Consumer<String> onError) {
            historySeenByModel.add(new ArrayList<>(messages));
            if (calls.incrementAndGet() == 1) {
                // 第一轮：模型**不走工具调用通道**，而是把调用写成正文（实测形态）
                pieces("好的\n{\"name\": \"get_my_gpa\", \"arguments\": {}}\n</tool_call>").forEach(onToken);
                // 刻意不调用 onToolCalls —— 真实客户端在"模型没用工具通道"时也不会回调它
            } else {
                // 第二轮：模型看到工具结果后正常作答
                pieces("你的平均学分绩点是 3.8834。").forEach(onToken);
            }
            onDone.run();
            if (calls.get() >= 2) {
                modelFinished.countDown();
            }
        }
    }

    private static List<String> pieces(String s) {
        // 逐字符喂入，顺带覆盖"标记被拆进多个增量"的真实流式情形
        List<String> out = new ArrayList<>();
        for (char c : s.toCharArray()) {
            out.add(String.valueOf(c));
        }
        return out;
    }

    @Test
    void textFormToolCallIsRecoveredAndExecuted() throws Exception {
        String token = jwtUtil.generateToken(STUDENT, "student");

        // 本测试走的是**真实落库**路径（后台线程没有事务，不能用 @Transactional 回滚），
        // 所以自己建会话、用完自己删——否则每跑一次测试，学生账号的会话列表里就多一条垃圾。
        AiConversation conversation = conversationService.create(STUDENT, "student", "护栏集成测试");
        String conversationId = conversation.getId();
        try {
            aiChatService.chat("我的绩点是多少？", token, conversationId);

            assertTrue(stubClient.modelFinished.await(15, TimeUnit.SECONDS), "模型桩应被调用两轮（恢复后的调用 + 收尾作答）");
            assertTrue(stubClient.calls.get() >= 2,
                    "恢复出的工具调用执行后必须回到模型继续，实际轮数=" + stubClient.calls.get());

            // ① 恢复出的调用**真的执行了**：审计表里有这条成功记录（说明走了白名单与参数校验）
            List<AiToolAudit> rows = auditMapper.listByUser(STUDENT, 5);
            assertNotNull(rows);
            AiToolAudit latest = rows.stream()
                    .filter(r -> "get_my_gpa".equals(r.getToolName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(latest, "应在审计表里看到恢复出的 get_my_gpa 调用；实际=" + rows);
            assertTrue("SUCCESS".equals(latest.getStatus()), "恢复出的调用应执行成功，实际状态=" + latest.getStatus());

            // ② 原始 JSON 没有被写回模型历史（否则模型会反复复述这段文本）
            assertTrue(stubClient.historySeenByModel.size() >= 2, "应有第二轮调用，才能检查历史");
            String secondRoundHistory = stubClient.historySeenByModel.get(1).toString();
            assertFalse(secondRoundHistory.contains("\"arguments\""),
                    "写回模型的助手消息里不应残留原始 JSON： " + secondRoundHistory);
            assertFalse(secondRoundHistory.contains("<tool_call>"),
                    "写回模型的助手消息里不应残留标签： " + secondRoundHistory);

            // ③ 落库的对话记录同样干净：护栏不只过滤"发给前端的流"，也过滤"进入多轮记忆的正文"。
            //    否则那段畸形 JSON 会成为下一轮的上下文，模型会把自己上次的坏输出再学一遍。
            List<AiMessage> persisted = messageMapper.listByConversation(conversationId);
            String transcript = persisted.toString();
            assertFalse(transcript.contains("\"arguments\""),
                    "落库的助手回复里不应残留原始工具调用 JSON：" + transcript);
            assertFalse(transcript.contains("tool_call"),
                    "落库的助手回复里不应残留工具调用标签：" + transcript);
            assertTrue(persisted.stream().anyMatch(m -> "assistant".equals(m.getRole())
                            && m.getContent().contains("3.8834")),
                    "模型最终的可见回答应被记入会话： " + transcript);
        } finally {
            conversationService.delete(conversationId, STUDENT);
            assertTrue(messageMapper.listByConversation(conversationId).isEmpty(),
                    "测试结束应清干净自己建的会话消息");
        }
    }
}

