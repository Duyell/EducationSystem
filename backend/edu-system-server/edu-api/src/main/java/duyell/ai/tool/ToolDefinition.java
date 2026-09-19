package duyell.ai.tool;

import java.util.Map;

/**
 * AI 工具定义。
 *
 * @param name        工具名（模型调用时使用的标识，需全局唯一）
 * @param displayName 中文展示名（用于前端确认卡片、日志、审计）
 * @param description 工具描述（模型靠它判断"何时该用这个工具"）
 * @param parameters  参数 JSON Schema
 * @param riskLevel   风险等级：是否为 DANGEROUS 决定是否需要用户确认
 * @param executor    执行逻辑
 */
public record ToolDefinition(
        String name,
        String displayName,
        String description,
        Map<String, Object> parameters,
        RiskLevel riskLevel,
        ToolExecutor executor
) {
    /**
     * 向后兼容构造器：未显式指定风险等级时按只读处理。
     *
     * <p>为避免"写工具忘了标注风险等级 → 被当作只读直接执行"，
     * {@code ToolRegistry} 会在注册时用命名启发式做一次自检（见 {@code ToolRiskAuditor} 日志）：
     * 工具名包含 <em>insert/update/delete/drop/enter/evaluate</em> 等写语义却标为 READ_ONLY 时给出 WARN。
     */
    public ToolDefinition(String name, String description,
                          Map<String, Object> parameters, ToolExecutor executor) {
        this(name, name, description, parameters, RiskLevel.READ_ONLY, executor);
    }

    /** 该工具名是否带有明显的"写操作"语义（注册期自检用） */
    public boolean looksLikeWriteOperation() {
        String n = name == null ? "" : name.toLowerCase();
        return n.contains("insert") || n.contains("update") || n.contains("delete")
                || n.contains("drop") || n.contains("enter") || n.contains("evaluate");
    }

    /** 是否必须在执行前获得用户确认 */
    public boolean requiresConfirmation() {
        return riskLevel != null && riskLevel.requiresConfirmation();
    }

    @FunctionalInterface
    public interface ToolExecutor {
        String execute(Map<String, Object> args, String userId, String role) throws Exception;
    }
}
