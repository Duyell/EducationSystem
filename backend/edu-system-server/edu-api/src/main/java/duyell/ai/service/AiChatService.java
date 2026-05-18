package duyell.ai.service;

import duyell.ai.config.AiProperties;
import duyell.ai.dto.ChatMessage;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import utils.JwtUtil;

import java.io.IOException;
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

    private static final int MAX_ITERATIONS = 10;
    private static final long SSE_TIMEOUT = 5 * 60 * 1000L; // 5 minutes

    private static final String SYSTEM_PROMPT_STUDENT = """
            你是一个智能的教务系统助手，帮助学生管理课程、成绩和教学评价。

            可用的功能：
            - 查看已选课程列表
            - 查看成绩
            - 选课和退课
            - 查看可选课程
            - 对教师进行教学评价
            - 查看评价状态和已提交的评价

            规则：
            - 选课和退课前务必先向用户确认
            - 回答简洁明了，数据用表格或列表展示
            - 成绩只读不可修改
            """;

    private static final String SYSTEM_PROMPT_TEACHER = """
            你是一个智能的教务系统助手，帮助教师管理课程和学生成绩。

            可用的功能：
            - 查看我的课程
            - 查看某门课程的学生名单
            - 录入学生成绩（平时成绩/考试成绩）
            - 修改已有成绩
            - 查看学生对我的评价

            规则：
            - 录入和修改成绩前务必向用户确认
            - 成绩计算公式：总成绩 = 平时成绩 × 0.4 + 考试成绩 × 0.6
            - 回答简洁明了，数据用表格或列表展示
            """;

    private static final String SYSTEM_PROMPT_ADMIN = """
            你是一个智能的教务系统助手，帮助管理员管理整个教务系统。

            可用的功能：
            - 查看系统统计数据
            - 查询用户列表
            - 查询学生/教师/课程/学院/专业/班级列表

            规则：
            - 当前仅支持查询操作，不支持数据修改
            - 查询结果最多返回50条
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

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // Background task to run the tool-calling loop
        CompletableFuture.runAsync(() -> {
            try {
                processChat(message, userId, role, emitter);
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
            emitter.complete();
        });
        emitter.onError(e -> log.error("SSE error for user {}", userId, e));
        emitter.onCompletion(() -> log.debug("SSE completed for user {}", userId));

        return emitter;
    }

    private void processChat(String message, String userId, String role,
                             SseEmitter emitter) throws Exception {

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
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
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
                String rawArgs = tc.getFunction().getArguments();

                sendSse(emitter, "message", Map.of(
                        "type", "status",
                        "content", "🔄 正在执行: " + toolName
                ));

                ToolDefinition toolDef = toolRegistry.getTool(toolName);
                String result;
                if (toolDef == null) {
                    result = "{\"error\":\"未知工具: " + toolName + "\"}";
                } else {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsedArgs = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                                .build()
                                .readValue(rawArgs, Map.class);
                        result = toolDef.executor().execute(parsedArgs, userId, role);
                    } catch (Exception e) {
                        log.error("Tool execution error: {}", toolName, e);
                        result = "{\"error\":\"执行失败: " + e.getMessage() + "\"}";
                    }
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

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", eventName, e);
        }
    }
}
