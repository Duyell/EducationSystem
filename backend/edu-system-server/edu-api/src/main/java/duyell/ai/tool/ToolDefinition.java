package duyell.ai.tool;

import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters,
        ToolExecutor executor
) {
    @FunctionalInterface
    public interface ToolExecutor {
        String execute(Map<String, Object> args, String userId, String role) throws Exception;
    }
}
