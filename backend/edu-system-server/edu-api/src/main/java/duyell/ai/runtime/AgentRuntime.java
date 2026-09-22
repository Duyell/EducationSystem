package duyell.ai.runtime;

import com.duyell.AiToolAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.audit.AiAuditService;
import duyell.ai.audit.AuditStatus;
import duyell.ai.config.AiProperties;
import duyell.ai.confirm.ConfirmationGate;
import duyell.ai.confirm.PendingAction;
import duyell.ai.confirm.PendingActionStore;
import duyell.ai.dto.ChatMessage;
import duyell.ai.guard.ToolCallTextGuard;
import duyell.ai.tool.ToolArgumentValidator;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 运行时：**只做编排**（M2 计划 1.4）。
 *
 * <p>它负责一轮对话里"模型 ↔ 工具"之间的往复：迭代上限、工具调用预算、以及每次工具调用
 * 必经的四道闸门（角色白名单 → 参数校验 → 危险操作人工确认 → 审计）。
 * 它**不管** HTTP、SSE 连接、token 解析、限流与会话持久化——那些留在 {@code AiChatService}。
 *
 * <p><b>为什么保留自写循环，而不是换成框架的 tool-calling 循环</b>（用户拍板 B）：
 * 框架的循环把"执行工具"当内部细节，而本项目要在执行**之前**挂起线程等人点确认卡片、
 * 在输出**之后**过滤模型写成正文的工具调用、并把每一次调用落审计表。
 * 换成框架循环就得把这三件事重写成 Advisor——那正是 M1 花力气建的闸门，
 * 重写窗口期风险最高。因此这里的选择是：**框架提供模型与工具能力，编排与控制留在自己手里**，
 * 并用 {@link AgentEventPublisher} 把"协议"与"传输"分开（可测、可换）。
 *
 * <p>返回 {@link Outcome} 而不是自己写库：助手正文的持久化属于"会话"关注点，
 * 由调用方（{@code AiChatService}）统一在收尾时落库——这样三条结束路径
 * （正常结束 / 用户取消 / 达到迭代上限）不会各写一遍、也不会漏写。
 *
 * @author duyell
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntime {

    private final duyell.ai.service.OpenAiClient openAiClient;
    private final ToolRegistry toolRegistry;
    private final AiProperties aiProperties;
    private final PendingActionStore pendingActionStore;
    private final ConfirmationGate confirmationGate;
    private final AiAuditService auditService;
    private final ToolArgumentValidator toolArgumentValidator;
    private final ObjectMapper objectMapper;

    /**
     * 本轮请求：谁、什么角色、什么提示词、带哪些历史、问了什么。
     *
     * @param onConfirmPending 挂起/解除挂起确认时回调（传 confirmId，解除时传 null）。
     *                         调用方（SSE 服务）据此在那条连接超时/断开时立刻释放等待，
     *                         而不必让线程一直阻塞到确认超时——**这是连接级状态，必须由调用方持有**，
     *                         运行时只负责告知"现在挂起的是哪个令牌"。
     */
    public record Request(String userId, String role, String systemPrompt,
                          List<ChatMessage> history, String userMessage,
                          java.util.function.Consumer<String> onConfirmPending) {

        /** 测试/内部使用：不关心连接级状态时用这个 */
        public static Request of(String userId, String role, String systemPrompt,
                                List<ChatMessage> history, String userMessage) {
            return new Request(userId, role, systemPrompt, history, userMessage, null);
        }
    }

    /**
     * 本轮结果。
     *
     * @param answerText 已经发给用户的**可见正文**（供调用方落库进入多轮记忆）
     * @param stopReason 结束原因：{@code completed} / {@code cancelled} / {@code max-iterations}
     *                   / {@code tool-budget-exhausted}
     */
    public record Outcome(String answerText, String stopReason) {

        public boolean cancelled() {
            return "cancelled".equals(stopReason);
        }
    }

    /** 危险操作确认结果（内部使用） */
    private record ConfirmationOutcome(boolean approved, String confirmId, String result) {
    }

    /**
     * 跑一轮对话。
     *
     * <p>是**同步**的：调用方决定放在哪个线程（生产是 SSE 的后台线程，测试可以直接调）。
     * 异常照旧往上抛，由调用方决定怎么报错——运行时不吞异常。
     */
    public Outcome run(Request request, AgentEventPublisher events) throws Exception {
        String userId = request.userId();
        String role = request.role();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role("system").content(request.systemPrompt()).build());
        if (request.history() != null) {
            messages.addAll(request.history());
        }
        messages.add(ChatMessage.builder().role("user").content(request.userMessage()).build());

        List<ToolDefinition> tools = toolRegistry.getToolsByRole(role);
        List<Map<String, Object>> toolsPayload = tools.isEmpty() ? null : toolRegistry.toToolsPayload(tools);

        int maxIterations = Math.max(1, aiProperties.getLimits().getMaxIterations());
        int maxToolCalls = aiProperties.getLimits().getMaxToolCallsPerChat();
        int toolCallsUsed = 0;

        // 本轮发给用户的可见正文（供多轮记忆）。只收集真正发出去的文本：
        // 被输出护栏扣下的原始工具调用 JSON 不进记忆，否则模型会把自己上次的畸形输出再学一遍。
        StringBuilder answerText = new StringBuilder();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean[] toolCallsReceived = {false};
            List<ChatMessage.ToolCall> currentToolCalls = new ArrayList<>();
            String[] currentReasoningContent = {null};

            // 输出护栏：小模型偶尔把工具调用**写成正文**（实测 qwen2.5:7b 会输出
            // `{"name": "...", "arguments": {...}}`）。它一边挡住这段原始 JSON 不展示给用户，
            // 一边把调用恢复出来，下面按**正常工具调用**继续走（白名单 / Schema 校验 / 确认卡片 / 审计全都不变）。
            ToolCallTextGuard guard = new ToolCallTextGuard(objectMapper);

            openAiClient.streamChat(
                    messages,
                    toolsPayload,
                    token -> {
                        String safe = guard.feed(token);
                        if (safe.isEmpty()) {
                            return;
                        }
                        answerText.append(safe);
                        events.publish(AgentEvent.token(safe));
                    },
                    toolCalls -> {
                        toolCallsReceived[0] = true;
                        currentToolCalls.addAll(toolCalls);
                    },
                    reasoning -> currentReasoningContent[0] = reasoning,
                    () -> {
                        // No-op, handled after streamChat returns
                    },
                    error -> {
                        events.publish(AgentEvent.error(error));
                        events.complete();
                    }
            );

            if (!toolCallsReceived[0]) {
                ToolCallTextGuard.Result recovered = guard.finish();
                if (recovered.calls().isEmpty()) {
                    // 正常结束：把护栏扣住的普通文本补发出去（不能吞掉回答）
                    if (!recovered.trailingText().isEmpty()) {
                        answerText.append(recovered.trailingText());
                        events.publish(AgentEvent.token(recovered.trailingText()));
                    }
                    events.publish(AgentEvent.done());
                    events.complete();
                    return new Outcome(answerText.toString(), "completed");
                }

                // 模型把工具调用写成了正文 → 恢复成真实调用继续走
                log.warn("模型把工具调用写成了正文，已恢复为真实调用: user={}, count={}, tools={}",
                        userId, recovered.calls().size(),
                        recovered.calls().stream().map(ToolCallTextGuard.RecoveredCall::name).toList());
                events.publish(AgentEvent.status("🔧 已识别模型以文本形式输出的工具调用，正在按正常流程处理"));
                int index = 0;
                for (ToolCallTextGuard.RecoveredCall call : recovered.calls()) {
                    currentToolCalls.add(ChatMessage.ToolCall.builder()
                            .id("recovered-" + UUID.randomUUID())
                            .type("function")
                            .index(index++)
                            .function(ChatMessage.ToolCall.Function.builder()
                                    .name(call.name())
                                    .arguments(call.arguments())
                                    .build())
                            .build());
                }
            }

            messages.add(ChatMessage.builder()
                    .role("assistant")
                    .content(null)
                    .reasoningContent(currentReasoningContent[0])
                    .toolCalls(currentToolCalls)
                    .build());

            for (ChatMessage.ToolCall tc : currentToolCalls) {
                String toolName = tc.getFunction().getName();

                // ①'' 单次对话工具调用总量闸门：防止模型在同一轮内狂调工具
                if (maxToolCalls > 0 && toolCallsUsed >= maxToolCalls) {
                    log.warn("单次对话工具调用数超限，提前结束: user={}, limit={}, used={}",
                            userId, maxToolCalls, toolCallsUsed);
                    events.publish(AgentEvent.status("⚠️ 单次对话工具调用次数已达上限，已停止继续执行"));
                    break;
                }
                toolCallsUsed++;
                String rawArgs = tc.getFunction().getArguments();

                // ① 角色白名单：模型给出的工具名不可信，越权/未知工具直接拒绝
                if (!toolRegistry.isAllowedForRole(role, toolName)) {
                    log.warn("拦截越权工具调用: role={}, user={}, tool={}", role, userId, toolName);
                    auditService.record(userId, role, toolName, "UNKNOWN",
                            parseArguments(rawArgs), null, AuditStatus.DENIED,
                            "角色 " + role + " 无权调用该工具或工具不存在", null, null);
                    events.publish(AgentEvent.error("已拦截越权工具调用: " + toolName, toolName));
                    messages.add(ChatMessage.builder()
                            .role("tool")
                            .toolCallId(tc.getId())
                            .name(toolName)
                            .content("{\"error\":\"TOOL_NOT_ALLOWED\","
                                    + "\"message\":\"当前角色不可调用该工具，请勿重试\"}")
                            .build());
                    continue;
                }

                // 定义必须按角色取：同名工具在不同角色下可能是**两份不同实现**
                ToolDefinition toolDef = toolRegistry.getTool(role, toolName);
                Map<String, Object> parsedArgs = parseArguments(rawArgs);

                // ①' 参数 Schema 校验：参数不合法就不该进入确认流程、更不该执行。
                // 把可读原因回灌给模型，让它自己改对参数重试（而不是抛异常或落库脏数据）。
                ToolArgumentValidator.Result validation =
                        toolArgumentValidator.validate(toolDef.parameters(), parsedArgs);
                if (!validation.valid()) {
                    log.info("工具参数校验未通过: tool={}, user={}, reason={}",
                            toolName, userId, validation.errorMessage());
                    auditService.record(userId, role, toolName, toolDef.riskLevel().name(),
                            parsedArgs, null, AuditStatus.INVALID_ARGUMENTS,
                            validation.errorMessage(), null, null);
                    events.publish(AgentEvent.status("⚠️ 参数不完整: " + displayName(toolDef),
                            toolName, displayName(toolDef), toolDef.riskLevel().name()));
                    messages.add(ChatMessage.builder()
                            .role("tool")
                            .toolCallId(tc.getId())
                            .name(toolName)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "error", "INVALID_ARGUMENTS",
                                    "message", validation.errorMessage(),
                                    "hint", "请根据上述问题修正参数后重新调用该工具")))
                            .build());
                    continue;
                }

                String result = null;

                // ② 危险操作必须经用户显式确认（HITL），不能只靠提示词约束
                if (toolDef.requiresConfirmation() && aiProperties.getConfirmation().isEnabled()) {
                    events.publish(AgentEvent.status("⏸ 等待确认: " + displayName(toolDef),
                            toolName, displayName(toolDef), toolDef.riskLevel().name()));

                    ConfirmationOutcome outcome =
                            awaitConfirmation(events, toolDef, parsedArgs, userId, role, request.onConfirmPending);
                    result = outcome.result();

                    // 用户取消/超时/连接断开后，工具调用链已无法继续（缺少 tool 结果会导致
                    // 后续请求被模型服务拒绝），因此直接收尾本次对话，让用户重新发起。
                    if (!outcome.approved()) {
                        // 用户拒绝/超时同样是审计要点：证明「未执行」是主动决策而非系统故障。
                        // 结果未写库，故不记 result_json，只记拒绝原因。
                        auditService.record(userId, role, toolName, toolDef.riskLevel().name(),
                                parsedArgs, null, AuditStatus.REJECTED_BY_USER,
                                "用户取消或确认超时，工具未执行", outcome.confirmId(), null);
                        // "已取消"是系统文案、不是模型输出，不入记忆（否则会被模型当成自己的话复述），
                        // 但**已经说出口的正文**要留着：下一轮追问时模型才知道自己刚才说过什么。
                        events.publish(AgentEvent.status("本次操作已取消，未对数据做任何修改"));
                        events.publish(AgentEvent.done());
                        events.complete();
                        return new Outcome(answerText.toString(), "cancelled");
                    }
                }

                if (result == null) {
                    // 带 tool/riskLevel：SSE 是调用方唯一能实时看到的「模型到底选了哪个工具」的
                    // 信号，此前只有一段中文文案，前端和评测脚本都只能靠猜。
                    events.publish(AgentEvent.status("🔄 正在执行: " + displayName(toolDef),
                            toolName, displayName(toolDef), toolDef.riskLevel().name()));
                    // ③ 统一执行入口：执行前已校验角色，异常信息脱敏后回灌模型
                    ToolExecutionResult exec = toolRegistry.executeForRole(toolDef, role, parsedArgs, userId);
                    result = exec.payload();
                    boolean failed = exec.status() == ToolExecutionResult.Status.FAILED;
                    if (failed) {
                        log.error("工具执行失败: tool={}, user={}, args={}, detail={}",
                                toolName, userId, parsedArgs, exec.errorDetail());
                    } else {
                        log.info("工具调用: tool={}, role={}, user={}, status={}, durationMs={}",
                                toolName, role, userId, exec.status(), exec.durationMs());
                    }
                    auditService.record(userId, role, toolName, toolDef.riskLevel().name(),
                            parsedArgs, result,
                            failed ? AuditStatus.FAILED : AuditStatus.SUCCESS,
                            exec.errorDetail(), null, exec.durationMs());
                }

                messages.add(ChatMessage.builder()
                        .role("tool")
                        .toolCallId(tc.getId())
                        .name(toolName)
                        .content(result)
                        .build());
            }
        }

        // 达到最大迭代次数：仍然可能已经给用户输出了一部分内容，必须落库（否则记忆里凭空少一轮）
        events.publish(AgentEvent.status("⚠️ 已达到最大迭代次数，部分操作可能未完成"));
        events.publish(AgentEvent.done());
        events.complete();
        return new Outcome(answerText.toString(), "max-iterations");
    }

    /**
     * 挂起等待用户确认危险操作。
     *
     * @return 用户确认时返回工具执行结果；取消/超时/失效时返回未批准
     */
    private ConfirmationOutcome awaitConfirmation(AgentEventPublisher events, ToolDefinition toolDef,
                                                 Map<String, Object> args, String userId, String role,
                                                 java.util.function.Consumer<String> onConfirmPending) {
        int timeoutSeconds = aiProperties.getConfirmation().getTimeoutSeconds();
        String confirmId = UUID.randomUUID().toString();
        PendingAction action = new PendingAction(confirmId, userId, role, toolDef.name(),
                displayName(toolDef), args, toolDef.riskLevel(), System.currentTimeMillis());
        pendingActionStore.save(action, Duration.ofSeconds(timeoutSeconds));
        // 告诉调用方"现在挂起的是这个令牌"：连接断了它就能立刻释放等待，不必耗满超时
        if (onConfirmPending != null) {
            onConfirmPending.accept(confirmId);
        }

        events.publish(AgentEvent.confirm(confirmId, toolDef.name(), displayName(toolDef), args, timeoutSeconds));

        ConfirmationGate.DecisionResult decision =
                confirmationGate.awaitDecision(confirmId, Duration.ofSeconds(timeoutSeconds));
        pendingActionStore.remove(confirmId);
        if (onConfirmPending != null) {
            onConfirmPending.accept(null);
        }

        switch (decision) {
            case APPROVED -> {
                events.publish(AgentEvent.confirmResult(confirmId, true, false));

                // 幂等保护：以确认令牌作为幂等键。同一个 confirmId 若已执行过，直接复用结果，
                // 绝不重复写库（ConfirmationGate 与 PendingActionStore 是第一道防线，
                // 这里是数据库层的最后一道，例如同一令牌被并发提交）。
                AiToolAudit alreadyDone = auditService.findExecuted(confirmId);
                if (alreadyDone != null) {
                    log.info("幂等命中，跳过重复执行: tool={}, user={}, confirmId={}",
                            toolDef.name(), userId, confirmId);
                    events.publish(AgentEvent.status("该操作此前已执行过，本次未重复执行"));
                    return new ConfirmationOutcome(true, confirmId, alreadyDone.getResultJson());
                }

                log.info("用户已确认危险操作: tool={}, user={}, args={}", toolDef.name(), userId, args);
                ToolExecutionResult exec = toolRegistry.executeForRole(toolDef, role, args, userId);
                boolean failed = exec.status() == ToolExecutionResult.Status.FAILED;
                if (failed) {
                    log.error("工具执行失败: tool={}, user={}, args={}, detail={}",
                            toolDef.name(), userId, args, exec.errorDetail());
                } else {
                    log.info("工具调用: tool={}, role={}, user={}, status={}, durationMs={}",
                            toolDef.name(), role, userId, exec.status(), exec.durationMs());
                }
                // 经人工确认后执行的写操作，是审计中最需要回溯的一类：记全 confirmId 与幂等键
                auditService.record(userId, role, toolDef.name(), toolDef.riskLevel().name(),
                        args, exec.payload(),
                        failed ? AuditStatus.FAILED : AuditStatus.SUCCESS,
                        exec.errorDetail(), confirmId, exec.durationMs(), confirmId);
                return new ConfirmationOutcome(true, confirmId, exec.payload());
            }
            case REJECTED -> {
                events.publish(AgentEvent.confirmResult(confirmId, false, false));
                log.info("用户取消危险操作: tool={}, user={}", toolDef.name(), userId);
                return new ConfirmationOutcome(false, confirmId, null);
            }
            default -> {
                events.publish(AgentEvent.confirmResult(confirmId, false, true));
                events.publish(AgentEvent.status("⚠️ 确认已超时，操作未执行"));
                log.info("危险操作确认超时: tool={}, user={}", toolDef.name(), userId);
                return new ConfirmationOutcome(false, confirmId, null);
            }
        }
    }

    /** 解析模型给出的工具参数；解析失败返回空 Map，由工具自身报参数缺失 */
    private Map<String, Object> parseArguments(String rawArgs) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawArgs,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("工具参数解析失败: {}", rawArgs, e);
            return Map.of();
        }
    }

    private String displayName(ToolDefinition toolDef) {
        return toolDef.displayName() != null && !toolDef.displayName().isBlank()
                ? toolDef.displayName() : toolDef.name();
    }
}
