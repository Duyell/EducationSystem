package duyell.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.duyell.AiToolAudit;
import duyell.ai.audit.AiAuditService;
import duyell.ai.audit.AuditStatus;
import duyell.ai.config.AiProperties;
import duyell.ai.confirm.ConfirmationGate;
import duyell.ai.confirm.PendingAction;
import duyell.ai.confirm.PendingActionStore;
import duyell.ai.dto.ChatMessage;
import duyell.ai.limit.AgentRateLimiter;
import duyell.ai.tool.ToolArgumentValidator;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import utils.JwtUtil;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final OpenAiClient openAiClient;
    private final ToolRegistry toolRegistry;
    private final JwtUtil jwtUtil;
    private final AiProperties aiProperties;
    private final PendingActionStore pendingActionStore;
    private final ConfirmationGate confirmationGate;
    private final AiAuditService auditService;
    private final ToolArgumentValidator toolArgumentValidator;
    private final AgentRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    private static final long SSE_TIMEOUT = 5 * 60 * 1000L; // 5 minutes

    /** 用户对危险操作的确认/取消请求（由 AiController 转发，userId 必须与服务端暂存记录一致） */
    public boolean resolveConfirmation(String confirmId, String userId, boolean approved) {
        PendingAction action = pendingActionStore.getForUser(confirmId, userId);
        if (action == null) {
            log.warn("确认请求无效或已过期: confirmId={}, userId={}", confirmId, userId);
            return false;
        }
        boolean decided = confirmationGate.decide(confirmId, approved);
        if (decided) {
            pendingActionStore.remove(confirmId);
        }
        return decided;
    }

    /** 浏览器断开 SSE 时立即释放挂起的确认等待，不必等超时 */
    public void cancelPendingConfirmation(String confirmId) {
        if (confirmId == null) return;
        confirmationGate.cancel(confirmId, "SSE completed");
        pendingActionStore.remove(confirmId);
    }

    private static final String SYSTEM_PROMPT_STUDENT = """
            你是一个智能的教务系统助手，帮助学生管理课程、成绩、培养方案与考试。

            可用的功能：
            - 查看已选课程列表、成绩、可选课程
            - 选课和退课（涉及修改，会弹确认卡片）
            - 对教师进行教学评价，查看评价状态
            - 我的培养方案：我适用的方案、必修/选修课程清单、建议修读学期
            - 毕业学分审核：必修是否全部通过、选修学分是否够、总学分还差多少
            - 我的绩点与**专业内排名**（只给本人的名次，不提供他人数据）
            - 推荐课程：按培养计划的缺口，从当前**可选**的课里挑
            - 我的考试安排（可只看未开考的）
            - 选课是否开放（管理员开关 + 时间窗），以及我现在能不能选/能不能退
            - 我的课表；以及某门课是否与我已选课程时间冲突

            规则：
            - 涉及数据修改的操作（选课/退课/评价）会由系统弹出确认卡片，用户确认后才真正执行；
              因此你不需要在文字里再追问"是否确认"，直接调用工具即可
            - 用户确认后，如实汇报执行结果；用户取消则说明未做修改，并询问是否需要其他帮助
            - **不要为了"可选参数"反问用户**：工具参数里标注为可选的（省略时有明确默认行为，
              例如学期默认取当前学期/全部）直接用默认值调用，先拿到数据再回答；
              只有**必填参数**缺失时才提问，并一次把缺的参数问清
               （实测：为一个可选的"学期"参数反问用户，会让本该直接给出的推荐变成一句反问，
               用户什么也没得到）
            - 回答"我学分够毕业吗""我该选什么课""我下周有考试吗"这类问题时，**先取数据再下结论**：
              毕业相关问题要先看培养方案与学分审核，推荐课程要先拿到缺口再匹配可选课，
              不要凭印象回答，也不要把"查不到"说成"没有要求"
            - 没有适用培养计划、选课未开放、课程没有排课等情况，都要**明确说出原因**，
              并告诉用户可以找谁处理，而不是返回一个空结果就结束
            - 选课时若提示时间冲突/已修过/超学分上限/名额已满，把**具体原因**转述给用户
            - 回答简洁明了，数据用表格或列表展示
            - 成绩只读不可修改
            - 只提供本人的数据：任何"查别人"的要求都拒绝
            """;

    private static final String SYSTEM_PROMPT_TEACHER = """
            你是一个智能的教务系统助手，帮助教师管理课程、成绩、开课申请与排课。

            可用的功能：
            - 查看我的课程
            - 查看某门课程的学生名单
            - 录入学生成绩（平时成绩/考试成绩）、修改已有成绩
            - 查看学生对我的评价
            - 我的课表（可只看某个学期）
            - 提交开课申请（会弹确认卡片，通过后由管理员审批）
            - 申请排课（会弹确认卡片；只需给出课程与时间，教室可省略由系统推荐）

            规则：
            - 录入/修改成绩、提交开课申请、申请排课都会由系统弹出确认卡片，
              用户确认后才真正执行；因此你不需要在文字里再追问"是否确认"，直接调用工具即可
            - **不要为了"可选参数"反问用户**：标注为可选的参数（如教室、期望周次）省略时有明确
              默认行为，直接用默认值调用；只有**必填参数**缺失时才提问，并一次把缺的参数问清
            - 排课申请提交后，如果工具返回了**冲突提示**，必须如实告诉教师"和哪门课/哪个教室撞了"，
              不要只说"提交成功"；最终是否通过由管理员审批决定
            - 只处理我本人所授课程的成绩与排课，非本人课程的请求直接拒绝并说明原因
            - 成绩计算公式：总成绩 = 平时成绩 × 0.4 + 考试成绩 × 0.6
            - **教师没有绩点与选课相关功能**：学生绩点、专业排名、毕业学分审核、选课/退课
              都不在你的权限范围内，被问到时要明确说明并建议对方用学生账号查看
            - 回答简洁明了，数据用表格或列表展示
            """;

    private static final String SYSTEM_PROMPT_ADMIN = """
            你是一个智能的教务系统助手，帮助管理员管理整个教务系统。

            可用的功能：
            - 查看系统统计数据
            - 查询用户/学生/教师/课程/学院/专业/班级列表
            - 审批教师的开课申请（会弹确认卡片）

            规则：
            - 查询结果最多返回50条
            - **不要为了"可选参数"反问用户**：查询类工具的筛选参数（角色/姓名/课程名等）都是可选的，
              省略即"不筛选"，直接用默认值调用；只有**必填参数**缺失时才提问
            - 审批开课申请**会真的生成一条开课记录**（课程代码沿用申请里的），
              所以只应在管理员明确要求时调用；系统会先弹确认卡片再执行
            - 审批只对"待审批"状态的申请有效，重复审批会被拒绝，如实转述原因
            - 教师没有绩点与选课功能；学生的绩点、专业排名、毕业审核属于学生本人或管理员的查询范围
            - 回答简洁明了，数据用表格或列表展示
            - 对于统计数据，给出清晰的汇总说明
            """;

    public SseEmitter chat(String message, String token) {
        // Parse token
        String userId;
        String role;
        try {
            userId = jwtUtil.getUsernameFromToken(token);
            role = jwtUtil.getRoleFromToken(token);
        } catch (Exception e) {
            log.warn("Invalid token in AI chat", e);
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(Map.of("type", "error", "content", "登录已过期，请重新登录")));
                emitter.complete();
            } catch (IOException ignored) {
            }
            return emitter;
        }

        // ① 对话频次限流（阶段 0.8）：在真正调用模型之前挡住超额请求，避免刷接口烧额度
        if (!rateLimiter.allowChat(userId, aiProperties.getLimits().getMaxChatsPerMinute())) {
            log.warn("对话频次超限，已拒绝: user={}, limit={}/分钟",
                    userId, aiProperties.getLimits().getMaxChatsPerMinute());
            SseEmitter limited = new SseEmitter(0L);
            try {
                limited.send(SseEmitter.event()
                        .name("message")
                        .data(Map.of("type", "error",
                                "content", "请求过于频繁，请稍后再试（每分钟最多 "
                                        + aiProperties.getLimits().getMaxChatsPerMinute() + " 次）")));
                limited.complete();
            } catch (IOException ignored) {
            }
            return limited;
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 当前挂起的待确认操作（每个会话同一时刻最多一个）
        final String[] awaitingConfirmId = {null};

        // Background task to run the tool-calling loop
        CompletableFuture.runAsync(() -> {
            try {
                processChat(message, userId, role, emitter, awaitingConfirmId);
            } catch (Exception e) {
                log.error("AI chat processing error", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(Map.of("type", "error", "content", "处理请求时出错: " + e.getMessage())));
                    emitter.complete();
                } catch (IOException ignored) {
                }
            }
        });

        // Handle timeout and completion
        emitter.onTimeout(() -> {
            log.info("SSE timeout for user {}", userId);
            // 连接已断，用户不可能再确认，立即释放挂起的确认等待
            cancelPendingConfirmation(awaitingConfirmId[0]);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.error("SSE error for user {}", userId, e);
            cancelPendingConfirmation(awaitingConfirmId[0]);
        });
        emitter.onCompletion(() -> {
            log.debug("SSE completed for user {}", userId);
            cancelPendingConfirmation(awaitingConfirmId[0]);
        });

        return emitter;
    }

    private void processChat(String message, String userId, String role,
                             SseEmitter emitter, String[] awaitingConfirmId) throws Exception {

        // Select system prompt by role
        String systemPrompt = switch (role) {
            case "admin" -> SYSTEM_PROMPT_ADMIN;
            case "teacher" -> SYSTEM_PROMPT_TEACHER;
            default -> SYSTEM_PROMPT_STUDENT;
        };

        // Build initial messages
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role("system").content(systemPrompt).build());
        messages.add(ChatMessage.builder().role("user").content(message).build());

        // Get tools for this role
        List<ToolDefinition> tools = toolRegistry.getToolsByRole(role);
        List<Map<String, Object>> toolsPayload = tools.isEmpty() ? null
                : toolRegistry.toToolsPayload(tools);

        // Tool-calling loop
        // 工具调用轮数上限改为可配置（原硬编码 10）
        int maxIterations = Math.max(1, aiProperties.getLimits().getMaxIterations());
        int maxToolCalls = aiProperties.getLimits().getMaxToolCallsPerChat();
        int toolCallsUsed = 0;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean[] toolCallsReceived = {false};
            List<ChatMessage.ToolCall> currentToolCalls = new ArrayList<>();
            String[] currentReasoningContent = {null};

            openAiClient.streamChat(
                    messages,
                    toolsPayload,
                    // onToken - forward to frontend
                    token -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(Map.of("type", "token", "content", token)));
                        } catch (IOException e) {
                            log.warn("Failed to send token to SSE", e);
                        }
                    },
                    // onToolCalls - tool calls from AI
                    toolCalls -> {
                        toolCallsReceived[0] = true;
                        currentToolCalls.addAll(toolCalls);
                    },
                    // onReasoningContent - reasoning from thinking models
                    reasoning -> currentReasoningContent[0] = reasoning,
                    // onDone - stream complete
                    () -> {
                        // No-op, handled after streamChat returns
                    },
                    // onError
                    error -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(Map.of("type", "error", "content", error)));
                            emitter.complete();
                        } catch (IOException e) {
                            log.warn("Failed to send error to SSE", e);
                        }
                    }
            );

            if (!toolCallsReceived[0]) {
                // No tool calls, response is complete
                sendSse(emitter, "done", Map.of("type", "done"));
                emitter.complete();
                return;
            }

            // Add assistant message with tool calls
            ChatMessage assistantMsg = ChatMessage.builder()
                    .role("assistant")
                    .content(null)
                    .reasoningContent(currentReasoningContent[0])
                    .toolCalls(currentToolCalls)
                    .build();
            messages.add(assistantMsg);

            // Execute each tool call
            for (ChatMessage.ToolCall tc : currentToolCalls) {
                String toolName = tc.getFunction().getName();

                // ①'' 单次对话工具调用总量闸门（阶段 0.8）：防止模型在同一轮内狂调工具
                if (maxToolCalls > 0 && toolCallsUsed >= maxToolCalls) {
                    log.warn("单次对话工具调用数超限，提前结束: user={}, limit={}, used={}",
                            userId, maxToolCalls, toolCallsUsed);
                    sendSse(emitter, "message", Map.of(
                            "type", "status",
                            "content", "⚠️ 单次对话工具调用次数已达上限，已停止继续执行"));
                    break;
                }
                toolCallsUsed++;
                String rawArgs = tc.getFunction().getArguments();

                // ① 角色白名单校验：模型给出的工具名不可信，越权/未知工具直接拒绝
                if (!toolRegistry.isAllowedForRole(role, toolName)) {
                    log.warn("拦截越权工具调用: role={}, user={}, tool={}", role, userId, toolName);
                    // 越权尝试是安全事件，必须留痕（这往往是最需要回溯的一类记录）
                    auditService.record(userId, role, toolName, unknownRiskLevel(toolName),
                            parseArguments(rawArgs), null, AuditStatus.DENIED,
                            "角色 " + role + " 无权调用该工具或工具不存在", null, null);
                    // tool 字段带上原始工具名：越权尝试往往需要被前端/评测脚本精确识别，
                    // 只给一段中文文案是没法机器判断的。
                    sendSse(emitter, "message", Map.of(
                            "type", "error",
                            "tool", toolName,
                            "content", "已拦截越权工具调用: " + toolName
                    ));
                    messages.add(ChatMessage.builder()
                            .role("tool")
                            .toolCallId(tc.getId())
                            .name(toolName)
                            .content("{\"error\":\"TOOL_NOT_ALLOWED\","
                                    + "\"message\":\"当前角色不可调用该工具，请勿重试\"}")
                            .build());
                    continue;
                }

                ToolDefinition toolDef = toolRegistry.getTool(toolName);
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
                    sendSse(emitter, "message", Map.of(
                            "type", "status",
                            "tool", toolName,
                            "displayName", displayName(toolDef),
                            "riskLevel", toolDef.riskLevel().name(),
                            "content", "⚠️ 参数不完整: " + displayName(toolDef)
                    ));
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
                if (toolDef.requiresConfirmation()
                        && aiProperties.getConfirmation().isEnabled()) {
                    sendSse(emitter, "message", Map.of(
                            "type", "status",
                            "tool", toolName,
                            "displayName", displayName(toolDef),
                            "riskLevel", toolDef.riskLevel().name(),
                            "content", "⏸ 等待确认: " + displayName(toolDef)
                    ));

                    ConfirmationOutcome outcome =
                            awaitConfirmation(emitter, toolDef, parsedArgs, userId, role, awaitingConfirmId);
                    result = outcome.result();

                    // 用户取消/超时/连接断开后，工具调用链已无法继续（缺少 tool 结果会导致
                    // 后续请求被模型服务拒绝），因此直接收尾本次对话，让用户重新发起。
                    if (!outcome.approved()) {
                        // 用户拒绝/超时同样是审计要点：证明「未执行」是主动决策而非系统故障。
                        // 结果未写库，故不记 result_json，只记拒绝原因。
                        auditService.record(userId, role, toolName, toolDef.riskLevel().name(),
                                parsedArgs, null, AuditStatus.REJECTED_BY_USER,
                                "用户取消或确认超时，工具未执行", outcome.confirmId(), null);
                        sendSse(emitter, "message", Map.of("type", "status",
                                "content", "本次操作已取消，未对数据做任何修改"));
                        sendSse(emitter, "done", Map.of("type", "done"));
                        emitter.complete();
                        return;
                    }
                }

                if (result == null) {
                    // 带 tool/riskLevel：SSE 是调用方唯一能实时看到的「模型到底选了哪个工具」的
                    // 信号，此前只有一段中文文案，前端和评测脚本都只能靠猜。
                    sendSse(emitter, "message", Map.of(
                            "type", "status",
                            "tool", toolName,
                            "displayName", displayName(toolDef),
                            "riskLevel", toolDef.riskLevel().name(),
                            "content", "🔄 正在执行: " + displayName(toolDef)
                    ));
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

                // Add tool result message
                messages.add(ChatMessage.builder()
                        .role("tool")
                        .toolCallId(tc.getId())
                        .name(toolName)
                        .content(result)
                        .build());
            }

            // Clear tools payload for subsequent iterations (tools already defined)
            // Actually keep tools for follow-up calls so AI can call them again if needed
        }

        // Max iterations reached
        sendSse(emitter, "message", Map.of(
                "type", "status",
                "content", "⚠️ 已达到最大迭代次数，部分操作可能未完成"
        ));
        sendSse(emitter, "done", Map.of("type", "done"));
        emitter.complete();
    }

    /**
     * 挂起等待用户确认危险操作。
     *
     * @return 用户确认时返回工具执行结果；取消/超时/失效时返回 null
     */
    /**
     * 危险操作确认结果。
     *
     * <p>显式带上 {@code confirmId}：它在方法内部会从「当前挂起」状态清除，
     * 而审计需要把它写进 confirm_id 列，所以必须随返回值带出，
     * 不能依赖调用方去读已被清空的挂起标记。
     *
     * @param approved 是否获批执行
     * @param confirmId 本次确认令牌
     * @param result 获批时的工具执行结果；未获批为 null
     */
    private record ConfirmationOutcome(boolean approved, String confirmId, String result) {
    }

    private ConfirmationOutcome awaitConfirmation(SseEmitter emitter, ToolDefinition toolDef,
                                                  Map<String, Object> args, String userId, String role,
                                                  String[] awaitingConfirmId)
            throws IOException {
        int timeoutSeconds = aiProperties.getConfirmation().getTimeoutSeconds();
        String confirmId = UUID.randomUUID().toString();
        PendingAction action = new PendingAction(confirmId, userId, role, toolDef.name(),
                displayName(toolDef), args, toolDef.riskLevel(), System.currentTimeMillis());
        pendingActionStore.save(action, Duration.ofSeconds(timeoutSeconds));
        awaitingConfirmId[0] = confirmId;

        sendSse(emitter, "message", Map.of(
                "type", "confirm",
                "confirmId", confirmId,
                "tool", toolDef.name(),
                "displayName", displayName(toolDef),
                "args", args,
                "timeoutSeconds", timeoutSeconds
        ));

        ConfirmationGate.DecisionResult decision =
                confirmationGate.awaitDecision(confirmId, Duration.ofSeconds(timeoutSeconds));
        pendingActionStore.remove(confirmId);
        awaitingConfirmId[0] = null;

        switch (decision) {
            case APPROVED -> {
                sendSse(emitter, "message", Map.of(
                        "type", "confirm_result", "confirmId", confirmId, "approved", true));

                // 幂等保护（阶段 0.6）：以确认令牌作为幂等键。
                // 同一个 confirmId 若已经成功执行过，直接复用既有结果，绝不重复写库。
                // 正常路径下 ConfirmationGate 与 PendingActionStore 已能挡住重复确认，
                // 这里是数据库层的最后一道防线（例如同一令牌被并发提交）。
                AiToolAudit alreadyDone = auditService.findExecuted(confirmId);
                if (alreadyDone != null) {
                    log.info("幂等命中，跳过重复执行: tool={}, user={}, confirmId={}",
                            toolDef.name(), userId, confirmId);
                    sendSse(emitter, "message", Map.of(
                            "type", "status",
                            "content", "该操作此前已执行过，本次未重复执行"));
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
                sendSse(emitter, "message", Map.of(
                        "type", "confirm_result", "confirmId", confirmId, "approved", false));
                log.info("用户取消危险操作: tool={}, user={}", toolDef.name(), userId);
                return new ConfirmationOutcome(false, confirmId, null);
            }
            default -> {
                sendSse(emitter, "message", Map.of(
                        "type", "confirm_result", "confirmId", confirmId,
                        "approved", false, "expired", true));
                sendSse(emitter, "message", Map.of(
                        "type", "status",
                        "content", "⚠️ 确认已超时，操作未执行"));
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

    /**
     * 越权/未知工具的风险等级。
     *
     * <p>刻意不返回该工具的真实等级：调用者本就无权使用它，
     * 泄露其是否存在、属于哪个等级都是不必要的权限信息外泄。
     */
    private String unknownRiskLevel(String toolName) {
        return "UNKNOWN";
    }

    private String displayName(ToolDefinition toolDef) {
        return toolDef.displayName() != null && !toolDef.displayName().isBlank()
                ? toolDef.displayName() : toolDef.name();
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", eventName, e);
        }
    }
}
