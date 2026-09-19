package duyell.ai.tool;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 工具注册中心：按角色维护可用工具，并作为工具执行的唯一入口。
 *
 * <p>安全要点：模型给出的工具名<b>不可信</b>。执行前必须校验
 * 「工具存在」且「该工具属于当前角色」，否则视为越权调用直接拒绝。
 * 旧实现用的是全局查找 {@code getTool(name)}，意味着学生角色理论上能触发教师工具。
 */
@Component
public class ToolRegistry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> allTools = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roleToolNames = new ConcurrentHashMap<>();

    public void register(String role, ToolDefinition tool) {
        allTools.put(tool.name(), tool);
        roleToolNames.computeIfAbsent(role, k -> new CopyOnWriteArraySet<>()).add(tool.name());

        // 注册期自检：写语义的工具若仍为 READ_ONLY，说明风险等级漏标（会绕过人工确认）
        if (tool.riskLevel() == RiskLevel.READ_ONLY && tool.looksLikeWriteOperation()) {
            log.warn("工具 [{}] 名称疑似写操作，但风险等级为 READ_ONLY，请确认是否应标为 WRITE/DANGEROUS",
                    tool.name());
        }
    }

    /**
     * 全局按名查找。仅供内部/payload 构建使用；
     * <b>执行工具请用 {@link #executeForRole}</b>，否则绕过角色白名单。
     */
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

    /** 该角色是否有权调用此工具（白名单校验） */
    public boolean isAllowedForRole(String role, String toolName) {
        if (role == null || toolName == null) return false;
        Set<String> names = roleToolNames.get(role);
        return names != null && names.contains(toolName);
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

    /**
     * 带角色白名单校验的工具执行入口。
     * 越权或未知工具不会抛异常，而是返回 {@link ToolExecutionResult#denied}，
     * 由调用方把拒绝原因作为 tool 消息回灌给模型（模型可据此自我纠正），同时必须记审计日志。
     */
    public ToolExecutionResult executeForRole(ToolDefinition def, String role,
                                              Map<String, Object> args, String userId) {
        long start = System.currentTimeMillis();
        if (def == null) {
            return ToolExecutionResult.denied("UNKNOWN_TOOL", "未知工具，已拒绝执行",
                    System.currentTimeMillis() - start);
        }
        if (!isAllowedForRole(role, def.name())) {
            return ToolExecutionResult.denied("ROLE_FORBIDDEN",
                    "当前角色(" + role + ")无权调用工具 " + def.name(),
                    System.currentTimeMillis() - start);
        }
        try {
            String result = def.executor().execute(args, userId, role);
            return ToolExecutionResult.ok(result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 异常细节只进日志，不回灌模型（避免泄露 SQL / 表结构 / 堆栈）
            return ToolExecutionResult.failed(e, System.currentTimeMillis() - start);
        }
    }
}
