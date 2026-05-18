package duyell.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.config.AiProperties;
import duyell.ai.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpenAiClient {

    private final HttpClient aiHttpClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public static class DeltaToolCall {
        public String id;
        public String type;
        public String functionName;
        public StringBuilder arguments = new StringBuilder();
        public int index;

        public DeltaToolCall() {}

        public DeltaToolCall(int index) {
            this.index = index;
        }
    }

    /**
     * 流式调用 OpenAI 兼容的 chat/completions 接口
     *
     * @param messages    消息列表
     * @param tools       工具列表（可选）
     * @param onToken     收到文本 token 时的回调
     * @param onToolCalls 工具调用完成后的回调（参数为 tool call 列表）
     * @param onDone      流结束时回调
     * @param onError     出错时回调
     */
    public void streamChat(List<ChatMessage> messages,
                           List<Map<String, Object>> tools,
                           Consumer<String> onToken,
                           Consumer<List<ChatMessage.ToolCall>> onToolCalls,
                           Consumer<String> onReasoningContent,
                           Runnable onDone,
                           Consumer<String> onError) {

        String apiKey = aiProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            onError.accept("AI API 密钥未配置，请设置环境变量 AI_API_KEY");
            return;
        }

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", aiProperties.getModel());
            requestBody.put("stream", true);

            List<Map<String, Object>> messageList = messages.stream()
                    .map(this::messageToMap)
                    .collect(Collectors.toList());
            requestBody.put("messages", messageList);

            if (tools != null && !tools.isEmpty()) {
                requestBody.put("tools", tools);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            log.debug("OpenAI request: {}", jsonBody);

            String baseUrl = aiProperties.getBaseUrl();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<java.io.InputStream> response = aiHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            if (statusCode != 200) {
                String errorBody = new String(response.body().readAllBytes());
                log.error("OpenAI API error: status={}, body={}", statusCode, errorBody);
                onError.accept("AI 服务请求失败: HTTP " + statusCode);
                return;
            }

            // Parse SSE stream
            Map<Integer, DeltaToolCall> toolCallMap = new LinkedHashMap<>();
            StringBuilder contentBuilder = new StringBuilder();
            StringBuilder reasoningContentBuilder = new StringBuilder();
            boolean hasToolCalls = false;
            boolean streamEnded = false;

            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }

                        try {
                            JsonNode json = objectMapper.readTree(data);
                            JsonNode choices = json.get("choices");
                            if (choices == null || choices.isEmpty()) continue;

                            JsonNode delta = choices.get(0).get("delta");
                            if (delta == null) continue;

                            // Text content
                            JsonNode content = delta.get("content");
                            if (content != null && !content.isNull()) {
                                String token = content.asText();
                                contentBuilder.append(token);
                                onToken.accept(token);
                            }

                            // Reasoning content (thinking/reasoning models)
                            JsonNode reasoningContent = delta.get("reasoning_content");
                            if (reasoningContent != null && !reasoningContent.isNull()) {
                                reasoningContentBuilder.append(reasoningContent.asText());
                            }

                            // Tool calls
                            JsonNode toolCallsNode = delta.get("tool_calls");
                            if (toolCallsNode != null && !toolCallsNode.isEmpty()) {
                                hasToolCalls = true;
                                for (JsonNode tc : toolCallsNode) {
                                    int idx = tc.has("index") ? tc.get("index").asInt() : 0;
                                    DeltaToolCall dtc = toolCallMap.computeIfAbsent(idx, DeltaToolCall::new);

                                    if (tc.has("id") && !tc.get("id").isNull()) {
                                        dtc.id = tc.get("id").asText();
                                    }
                                    if (tc.has("type") && !tc.get("type").isNull()) {
                                        dtc.type = tc.get("type").asText();
                                    }
                                    JsonNode func = tc.get("function");
                                    if (func != null) {
                                        if (func.has("name") && !func.get("name").isNull()) {
                                            dtc.functionName = func.get("name").asText();
                                        }
                                        if (func.has("arguments") && !func.get("arguments").isNull()) {
                                            dtc.arguments.append(func.get("arguments").asText());
                                        }
                                    }
                                }
                            }

                            // Finish reason
                            JsonNode finishReason = choices.get(0).get("finish_reason");
                            if (finishReason != null && !finishReason.isNull()) {
                                String reason = finishReason.asText();
                                if ("tool_calls".equals(reason)) {
                                    streamEnded = true;
                                } else if ("stop".equals(reason)) {
                                    streamEnded = true;
                                }
                            }

                        } catch (Exception e) {
                            log.warn("Failed to parse SSE line: {}", line, e);
                        }
                    }
                }
            }

            if (hasToolCalls && streamEnded) {
                String reasoning = reasoningContentBuilder.length() > 0
                        ? reasoningContentBuilder.toString() : null;
                if (reasoning != null) {
                    onReasoningContent.accept(reasoning);
                }

                List<ChatMessage.ToolCall> resultToolCalls = new ArrayList<>();
                for (DeltaToolCall dtc : toolCallMap.values()) {
                    ChatMessage.ToolCall tc = ChatMessage.ToolCall.builder()
                            .id(dtc.id != null ? dtc.id : "call_" + UUID.randomUUID())
                            .type(dtc.type != null ? dtc.type : "function")
                            .function(ChatMessage.ToolCall.Function.builder()
                                    .name(dtc.functionName)
                                    .arguments(dtc.arguments.toString())
                                    .build())
                            .build();
                    resultToolCalls.add(tc);
                }
                onToolCalls.accept(resultToolCalls);
                onDone.run();
            } else if (streamEnded) {
                onDone.run();
            } else {
                onDone.run();
            }

        } catch (Exception e) {
            log.error("OpenAI stream chat error", e);
            onError.accept("AI 服务调用异常: " + e.getMessage());
        }
    }

    private Map<String, Object> messageToMap(ChatMessage msg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", msg.getRole());
        if (msg.getContent() != null) {
            map.put("content", msg.getContent());
        } else {
            map.put("content", "");
        }

        if (msg.getReasoningContent() != null) {
            map.put("reasoning_content", msg.getReasoningContent());
        }

        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                Map<String, Object> tcMap = new LinkedHashMap<>();
                tcMap.put("id", tc.getId());
                tcMap.put("type", tc.getType());
                Map<String, Object> funcMap = new LinkedHashMap<>();
                funcMap.put("name", tc.getFunction().getName());
                funcMap.put("arguments", tc.getFunction().getArguments());
                tcMap.put("function", funcMap);
                tcList.add(tcMap);
            }
            map.put("tool_calls", tcList);
        }

        if (msg.getToolCallId() != null) {
            map.put("tool_call_id", msg.getToolCallId());
        }
        if (msg.getName() != null) {
            map.put("name", msg.getName());
        }
        return map;
    }
}
