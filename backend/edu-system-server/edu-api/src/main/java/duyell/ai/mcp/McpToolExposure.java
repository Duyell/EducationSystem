package duyell.ai.mcp;

import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * **MCP 工具暴露策略**：哪些工具可以开放给外部 MCP 客户端。
 *
 * <p><b>为什么需要一个单独的类，而不是在装配处写个 filter</b>：这是本项目里唯一一处
 * "把内部能力交给**外部进程**"的地方，安全边界必须能被单独阅读、单独测试。
 * 装配代码只负责"把策略的输出交给 MCP server"，判断留在这一处
 * （与 {@code ToolRegistry} 的"定义按角色隔离"、{@code RiskLevel} 的"风险是代码硬约束"同一思路）。
 *
 * <p><b>三级放行规则</b>：
 * <ol>
 *   <li>{@link RiskLevel#READ_ONLY} —— 始终放行（本次不做任何数据变更）；</li>
 *   <li>{@link RiskLevel#WRITE} —— 仅当 {@code ai.mcp.allow-writes=true} 时放行。它是可逆的小改动，
 *       不存在"必须人工确认"的硬要求，但仍属于**外部客户端能改我们的数据**，默认关闭；</li>
 *   <li>{@link RiskLevel#DANGEROUS} —— <b>永不放行</b>，即使显式配置也不放行。
 *       理由不是保守，而是**协议层面做不到**：这类工具的既有保证来自 HITL
 *       （服务端挂起 SSE 流、等用户在界面上点"确认"，见 {@code AgentRuntime}）。
 *       MCP 没有这条通道，一旦放出去，就等于把"选课/录成绩"变成外部客户端**一次调用即可完成**的操作，
 *       而确认卡片永远不会出现。所以这里的选择是"拒绝暴露"，而不是"暴露后再想办法确认"。</li>
 * </ol>
 *
 * <p>顺带一个可见的好处：被排除的工具**带上原因**返回（{@link Excluded}），
 * 启动日志里能直接说清"哪些工具为什么没开放"——否则使用者只会看到工具少了一半，
 * 然后开始怀疑是不是配置写错了。
 *
 * @author duyell
 */
public final class McpToolExposure {

    /** 一个被排除的工具及原因（用于启动日志与测试断言） */
    public record Excluded(String name, RiskLevel riskLevel, String reason) {
    }

    /** 暴露结果：可放行的工具 + 被排除的工具（两者合起来必须等于该角色的全部工具） */
    public record Exposure(List<ToolDefinition> exposed, List<Excluded> excluded) {

        public List<String> exposedNames() {
            return exposed.stream().map(ToolDefinition::name).toList();
        }
    }

    private final ToolRegistry toolRegistry;
    private final boolean allowWrites;

    public McpToolExposure(ToolRegistry toolRegistry, boolean allowWrites) {
        this.toolRegistry = toolRegistry;
        this.allowWrites = allowWrites;
    }

    /**
     * 计算某角色的 MCP 工具面。
     *
     * @param role 工具面所属角色（配置项，不由客户端自称）
     */
    public Exposure forRole(String role) {
        List<ToolDefinition> exposed = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        for (ToolDefinition def : toolRegistry.getToolsByRole(role)) {
            RiskLevel risk = def.riskLevel() == null ? RiskLevel.READ_ONLY : def.riskLevel();
            switch (risk) {
                case READ_ONLY -> exposed.add(def);
                case WRITE -> {
                    if (allowWrites) {
                        exposed.add(def);
                    } else {
                        excluded.add(new Excluded(def.name(), risk,
                                "WRITE 工具默认不开放给外部客户端（需要 ai.mcp.allow-writes=true）"));
                    }
                }
                case DANGEROUS -> excluded.add(new Excluded(def.name(), risk,
                        "DANGEROUS 工具永不开放：它的安全性依赖人工确认（HITL），而 MCP 没有这条通道"));
            }
        }
        return new Exposure(List.copyOf(exposed), List.copyOf(excluded));
    }
}
