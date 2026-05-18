package duyell.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    private String role;
    private String content;
    private String reasoningContent;
    private List<ToolCall> toolCalls;
    private String toolCallId;
    private String name;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String id;
        private String type;
        private Function function;
        private Integer index;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Function {
            private String name;
            private String arguments;
        }
    }
}
