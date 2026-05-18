package duyell.ai.tool;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> allTools = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roleToolNames = new ConcurrentHashMap<>();

    public void register(String role, ToolDefinition tool) {
        allTools.put(tool.name(), tool);
        roleToolNames.computeIfAbsent(role, k -> new CopyOnWriteArraySet<>()).add(tool.name());
    }

    public ToolDefinition getTool(String name) {
        return allTools.get(name);
    }

    public List<ToolDefinition> getToolsByRole(String role) {
        Set<String> names = roleToolNames.get(role);
        if (names == null || names.isEmpty()) return List.of();
        return names.stream()
                .map(allTools::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<Map<String, Object>> toToolsPayload(List<ToolDefinition> toolDefs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolDefinition def : toolDefs) {
            Map<String, Object> toolObj = new LinkedHashMap<>();
            toolObj.put("type", "function");
            Map<String, Object> func = new LinkedHashMap<>();
            func.put("name", def.name());
            func.put("description", def.description());
            func.put("parameters", def.parameters() != null ? def.parameters() : Map.of("type", "object", "properties", Map.of()));
            toolObj.put("function", func);
            list.add(toolObj);
        }
        return list;
    }
}
