package duyell.ai.runtime;

import com.duyell.AiToolAudit;
import com.duyell.Course;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.audit.AiAuditService;
import duyell.ai.config.AiProperties;
import duyell.ai.confirm.ConfirmationGate;
import duyell.ai.confirm.PendingActionStore;
import duyell.ai.dto.ChatMessage;
import duyell.ai.service.OpenAiClient;
import duyell.ai.tool.ToolArgumentValidator;
import duyell.ai.tool.ToolRegistry;
import duyell.mapper.AiToolAuditMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRuntime} 的**协议级**测试（M2 计划 1.4）。
 *
 * <p>抽出 {@link AgentEventPublisher} 的直接收益就在这里：不必起 HTTP、不必解析 SSE 文本，
 * 就能对"一轮对话应该产出什么样的事件序列"下断言。而跑的仍是**生产同一段编排代码**。
 *
 * <p>盯住的四件事（都是"重构最容易弄坏、而且不会编译报错"的）：
 * <ol>
 *   <li><b>只读回答</b>：token 流出去 + 以 done 收尾，不弹确认卡片；</li>
 *   <li><b>输出护栏接线仍在</b>：模型把工具调用写成正文时，仍被恢复成真实调用执行，
 *       且原始 JSON / 协议标签**不进任何事件**，也不进 {@link AgentRuntime.Outcome#answerText()}
 *       （后者会被落库进多轮记忆）；</li>
 *   <li><b>HITL 双向</b>：批准 → 真的执行（数据库有痕迹、审计带 confirmId）；
 *       拒绝 → **一次都不执行**、给出"已取消"、并以 {@code cancelled} 收尾；</li>
 *   <li><b>越权拦截</b>：学生调用教师工具被拒、记 DENIED 审计，且错误事件带原始工具名。</li>
 * </ol>
 *
 * <p>模型用脚本桩（继承 {@code OpenAiClient} 覆盖 streamChat）：真模型是异步且带随机的，
 * 这些协议行为必须**确定性**可测。真实模型链路另有 {@code OutputGuardrailIntegrationTest}
 * 与 {@code .dsh/verify-real-llm.cjs} 覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AgentRuntimeTest {

    private static final String STUDENT = "2023001";
    private static final String STUDENT_ROLE = "student";
    private static final String SYSTEM_PROMPT = "你是教务助手";

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private PendingActionStore pendingActionStore;

    @Autowired
    private ConfirmationGate confirmationGate;

    @Autowired
    private AiAuditService auditService;

    @Autowired
    private ToolArgumentValidator toolArgumentValidator;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiToolAuditMapper auditMapper;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private CourseSelectionMapper courseSelectionMapper;

    private ScriptedOpenAiClient model;
    private AgentRuntime runtime;

    /**
     * **手工装配运行时**，而不是往 Spring 上下文里塞一个"桩模型"Bean。
     *
     * <p>原因：{@code OpenAiClient} 的桩若是 {@code @Primary} Bean，会和别的测试类
     * （{@code OutputGuardrailIntegrationTest}）里同样标了 {@code @Primary} 的桩撞车——
     * Spring 的上下文缓存会把两个测试配置合进同一个上下文，注入直接报
     * {@code more than one 'primary' bean found}。手工装配既绕开这类互相干扰，
     * 也让"被测对象依赖了什么"一目了然。
     */
    @BeforeEach
    void setUpRuntime() {
        model = new ScriptedOpenAiClient(HttpClient.newHttpClient(), aiProperties, objectMapper);
        runtime = new AgentRuntime(model, toolRegistry, aiProperties, pendingActionStore,
                confirmationGate, auditService, toolArgumentValidator, objectMapper);
    }

    /** 模型桩：按"第几轮"播放脚本，每轮要么吐文本、要么发工具调用 */
    static class ScriptedOpenAiClient extends OpenAiClient {

        interface Round {
            void play(Consumer<String> onToken, Consumer<List<ChatMessage.ToolCall>> onToolCalls);
        }

        final List<Round> rounds = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<List<ChatMessage>> historySeenByModel = new ArrayList<>();

        ScriptedOpenAiClient(HttpClient httpClient, AiProperties aiProperties, ObjectMapper objectMapper) {
            super(httpClient, aiProperties, objectMapper);
        }

        ScriptedOpenAiClient script(Round round) {
            rounds.add(round);
            return this;
        }

        void reset() {
            rounds.clear();
            calls.set(0);
            historySeenByModel.clear();
        }

        @Override
        public void streamChat(List<ChatMessage> messages,
                               List<Map<String, Object>> tools,
                               Consumer<String> onToken,
                               Consumer<List<ChatMessage.ToolCall>> onToolCalls,
                               Consumer<String> onReasoningContent,
                               Runnable onDone,
                               Consumer<String> onError) {
            historySeenByModel.add(new ArrayList<>(messages));
            int index = calls.getAndIncrement();
            if (index >= rounds.size()) {
                onToken.accept("（脚本已耗尽）");
            } else {
                rounds.get(index).play(onToken, onToolCalls);
            }
            onDone.run();
        }
    }

    /** 记录型事件出口：直接读事件序列，不需要 HTTP */
    static class RecordingPublisher implements AgentEventPublisher {
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        volatile boolean completed = false;

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public void complete() {
            completed = true;
        }

        List<AgentEventType> types() {
            return events.stream().map(AgentEvent::type).toList();
        }

        AgentEvent first(AgentEventType type) {
            return events.stream().filter(e -> e.type() == type).findFirst().orElse(null);
        }

        AgentEvent last(AgentEventType type) {
            AgentEvent found = null;
            for (AgentEvent e : events) {
                if (e.type() == type) {
                    found = e;
                }
            }
            return found;
        }

        /** 所有事件里出现过的文本（含 status/error），用于"任何地方都不许出现原始 JSON"这类断言 */
        String allText() {
            return events.stream().map(AgentEvent::content).filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        }
    }

    /** 逐字符吐文本，模拟真实流式（护栏的分片边界就是这么被踩出来的） */
    private static Consumer<String> charByChar(Consumer<String> sink) {
        return text -> {
            for (char c : text.toCharArray()) {
                sink.accept(String.valueOf(c));
            }
        };
    }

    private static ChatMessage.ToolCall toolCall(String id, String name, String argsJson) {
        return ChatMessage.ToolCall.builder()
                .id(id).type("function").index(0)
                .function(ChatMessage.ToolCall.Function.builder().name(name).arguments(argsJson).build())
                .build();
    }

    private AgentRuntime.Request request(String message) {
        return AgentRuntime.Request.of(STUDENT, STUDENT_ROLE, SYSTEM_PROMPT, List.of(), message);
    }

    /** 取一门该生**尚未选**的课（避免依赖某个固定 id） */
    private Integer unselectedCourseId() {
        for (Course course : courseMapper.list(null, null, null, null, null, null, null)) {
            if (courseSelectionMapper.select(course.getId(), STUDENT) == null) {
                return course.getId();
            }
        }
        throw new IllegalStateException("找不到未选课程，无法测试选课路径");
    }

    /**
     * 在另一个线程里等待确认事件并做出决定。
     *
     * <p>必须另起线程：运行时在 {@code awaitDecision} 上同步阻塞，测试线程要留给它。
     * 这里只操作确认闸门（内存闩锁），不碰数据库，因此不受测试事务影响。
     */
    private void decideWhenAsked(RecordingPublisher events, boolean approved) {
        Thread decider = new Thread(() -> {
            for (int i = 0; i < 500; i++) {
                AgentEvent confirm = events.first(AgentEventType.CONFIRM);
                if (confirm != null) {
                    confirmationGate.decide(confirm.confirmId(), approved);
                    return;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        decider.setDaemon(true);
        decider.start();
    }

    @Test
    void plainAnswerStreamsTokensAndEndsWithDone() throws Exception {
        model.reset();
        model.script((onToken, onToolCalls) -> charByChar(onToken).accept("你的平均学分绩点是 3.8834。"));
        RecordingPublisher events = new RecordingPublisher();

        AgentRuntime.Outcome outcome = runtime.run(request("我绩点多少"), events);

        assertEquals("completed", outcome.stopReason());
        assertEquals("你的平均学分绩点是 3.8834。", outcome.answerText(), "流式正文必须原样累积（会落库进记忆）");
        assertTrue(events.types().contains(AgentEventType.TOKEN));
        assertEquals(AgentEventType.DONE, events.types().get(events.types().size() - 1), "最后一个事件必须是 done");
        assertTrue(events.completed, "结束时必须关闭输出通道");
        assertNull(events.first(AgentEventType.CONFIRM), "只读回答不该弹确认卡片");
        assertFalse(events.types().contains(AgentEventType.ERROR));
    }

    /** 输出护栏仍然接在运行时里：恢复出的调用被执行，且原始 JSON 不进事件、不进记忆文本 */
    @Test
    void textFormToolCallIsRecoveredAndExecutedWithoutLeakingRawJson() throws Exception {
        model.reset();
        model.script((onToken, onToolCalls) ->
                // 实测形态：一上来是噪声 + 裸 JSON + 落单闭合标签；刻意不调用 onToolCalls
                charByChar(onToken).accept("好的\n{\"name\": \"get_my_gpa\", \"arguments\": {}}\n</tool_call>"));
        model.script((onToken, onToolCalls) -> charByChar(onToken).accept("绩点 3.8834。"));
        RecordingPublisher events = new RecordingPublisher();

        AgentRuntime.Outcome outcome = runtime.run(request("我绩点多少"), events);

        assertEquals("completed", outcome.stopReason());
        assertEquals(2, model.calls.get(), "恢复出的调用执行后必须回到模型继续（两轮）");

        // ① 恢复出的调用真的执行了：审计表里有一条成功的 get_my_gpa
        AiToolAudit audit = auditMapper.listByUser(STUDENT, 5).stream()
                .filter(r -> "get_my_gpa".equals(r.getToolName())).findFirst().orElse(null);
        assertNotNull(audit, "恢复出的工具调用必须落审计");
        assertEquals("SUCCESS", audit.getStatus());

        // ② 原始 JSON 与协议标签既不进事件、也不进"要给模型看的正文"
        String visible = events.allText();
        assertFalse(visible.contains("\"arguments\""), "事件里不该出现原始 JSON：" + visible);
        assertFalse(visible.contains("tool_call"), "事件里不该出现协议标签：" + visible);
        assertFalse(outcome.answerText().contains("\"arguments\""), "记忆文本里不该出现原始 JSON");
        assertTrue(outcome.answerText().contains("3.8834"), "真正的回答要保留：" + outcome.answerText());

        // ③ 写回模型的历史里也不能残留畸形输出（否则模型会反复复述它）
        String secondRound = model.historySeenByModel.get(1).toString();
        assertFalse(secondRound.contains("\"arguments\""), "回灌模型的历史里不该有原始 JSON：" + secondRound);
    }

    /** 危险操作：批准后**真的执行**，且审计带上确认令牌 */
    @Test
    void approvedDangerousToolExecutesAndIsAuditedWithConfirmId() throws Exception {
        model.reset();
        Integer courseId = unselectedCourseId();
        model.script((onToken, onToolCalls) ->
                onToolCalls.accept(List.of(toolCall("call-1", "select_course", "{\"courseId\":" + courseId + "}"))));
        model.script((onToken, onToolCalls) -> charByChar(onToken).accept("已为你选课。"));
        RecordingPublisher events = new RecordingPublisher();
        decideWhenAsked(events, true);

        AgentRuntime.Outcome outcome = runtime.run(request("帮我选课"), events);

        assertEquals("completed", outcome.stopReason());
        AgentEvent confirm = events.first(AgentEventType.CONFIRM);
        assertNotNull(confirm, "危险操作必须先挂起等确认");
        assertEquals("select_course", confirm.tool());
        assertEquals("选课", confirm.displayName(), "确认卡片上要给人看的名字");
        assertNotNull(confirm.args(), "确认卡片要展示参数，用户才知道自己在确认什么");
        assertNotNull(confirm.timeoutSeconds());

        AgentEvent result = events.first(AgentEventType.CONFIRM_RESULT);
        assertNotNull(result, "决定了必须回传结果");
        assertEquals(true, result.approved());
        assertEquals(confirm.confirmId(), result.confirmId());

        assertNotNull(courseSelectionMapper.select(courseId, STUDENT), "批准后必须真的写库");
        assertNotNull(auditService.findExecuted(confirm.confirmId()),
                "经人工确认的写操作必须能按确认令牌回溯（幂等键）");
    }

    /** 拒绝：一次都不执行、明确告知已取消、以 cancelled 收尾 */
    @Test
    void declinedDangerousToolNeverExecutesAndEndsTheTurn() throws Exception {
        model.reset();
        Integer courseId = unselectedCourseId();
        model.script((onToken, onToolCalls) ->
                onToolCalls.accept(List.of(toolCall("call-1", "select_course", "{\"courseId\":" + courseId + "}"))));
        RecordingPublisher events = new RecordingPublisher();
        decideWhenAsked(events, false);

        AgentRuntime.Outcome outcome = runtime.run(request("帮我选课"), events);

        assertEquals("cancelled", outcome.stopReason(), "取消后必须收尾这一轮（缺 tool 结果会让后续请求被模型服务拒绝）");
        assertTrue(outcome.cancelled());
        assertEquals(false, events.first(AgentEventType.CONFIRM_RESULT).approved());
        assertTrue(events.allText().contains("已取消"), "必须明确告诉用户没有做修改：" + events.allText());
        assertEquals(AgentEventType.DONE, events.types().get(events.types().size() - 1));

        assertNull(courseSelectionMapper.select(courseId, STUDENT), "拒绝后绝不能写库");
        assertNull(auditService.findExecuted(events.first(AgentEventType.CONFIRM).confirmId()),
                "未执行就不该有可回溯的执行记录");
        assertEquals("REJECTED_BY_USER", auditMapper.listByUser(STUDENT, 3).get(0).getStatus(),
                "拒绝同样要留痕：证明'未执行'是主动决策而不是系统故障");
    }

    /** 越权工具：拒绝 + 记 DENIED + 错误事件里带原始工具名（脚本要能机器识别） */
    @Test
    void forbiddenToolIsDeniedAuditedAndTheTurnContinues() throws Exception {
        model.reset();
        model.script((onToken, onToolCalls) ->
                onToolCalls.accept(List.of(toolCall("call-1", "enter_score",
                        "{\"courseId\":10,\"studentId\":\"2024002\",\"usualScore\":90,\"examScore\":90}"))));
        model.script((onToken, onToolCalls) -> charByChar(onToken).accept("抱歉，我没有这个权限。"));
        RecordingPublisher events = new RecordingPublisher();

        AgentRuntime.Outcome outcome = runtime.run(request("帮我给这个学生录成绩"), events);

        AgentEvent error = events.first(AgentEventType.ERROR);
        assertNotNull(error, "越权调用必须报错");
        assertEquals("enter_score", error.tool(), "错误事件要带原始工具名，脚本才能机器判断");
        assertTrue(error.content().contains("越权"), error.content());

        AiToolAudit audit = auditMapper.listByUser(STUDENT, 5).stream()
                .filter(r -> "enter_score".equals(r.getToolName())).findFirst().orElse(null);
        assertNotNull(audit, "越权尝试必须留痕（这往往是最需要回溯的一类记录）");
        assertEquals("DENIED", audit.getStatus());

        // 拒绝一次不该终止整轮：把拒绝原因回灌模型后，它还能继续作答
        assertEquals("completed", outcome.stopReason());
        assertTrue(outcome.answerText().contains("权限"), "后续作答也要保留：" + outcome.answerText());
    }
}
