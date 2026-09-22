package duyell.ai.tool;

import duyell.audit.ChangeContext;
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
 *
 * <p>⚠️ <b>定义必须按角色隔离，不能只按工具名索引</b>（2026-09-22 修）：
 * 学生与教师各有一个叫 {@code get_my_courses} 的工具，但语义不同
 * （学生的＝"我选的课"，教师的＝"我教的课"）。早期实现把定义放在**一个全局 Map** 里，
 * 于是两个注册器互相覆盖——注册顺序决定谁能活下来。症状极具迷惑性：
 * <ul>
 *   <li>学生角色的白名单校验**通过**（名字确实在学生的名单里），</li>
 *   <li>但执行的是**教师那份实现**，用学号当教师工号去查 → 返回空列表；</li>
 *   <li>模型还会拿到教师版描述，等于从提示词层面就开始误导。</li>
 * </ul>
 * 这个问题在真机上表现为"学生问'我选了什么课'，助手答'你没有选任何课'"，
 * 而所有既有测试都是绿的（工具面测试只看"工具在不在、角色对不对"，
 * 评测脚本只看"模型选没选对工具"——**没有一处看过工具返回的数据**）。
 * 现在同一工具名在不同角色下是**两份独立定义**，互不可见；
 * 跨角色同名只记一条日志（这是合法用法，但值得在日志里留痕）。
 */
@Component
public class ToolRegistry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolRegistry.class);

    /** 角色 → 该角色的工具定义（按名索引）。定义**不跨角色共享** */
    private final Map<String, Map<String, ToolDefinition>> roleTools = new ConcurrentHashMap<>();
    /** 角色 → 工具名集合。用 Set 只为保留注册顺序（payload 顺序稳定，便于比对与排查） */
    private final Map<String, Set<String>> roleToolNames = new ConcurrentHashMap<>();

    public void register(String role, ToolDefinition tool) {
        Map<String, ToolDefinition> tools = roleTools.computeIfAbsent(role, k -> new ConcurrentHashMap<>());
        // 同一角色内同名重复注册仍是静默覆盖（症状是"某个工具的描述莫名其妙变了"），必须留痕
        ToolDefinition previous = tools.put(tool.name(), tool);
        if (previous != null) {
            log.warn("角色 [{}] 的工具 [{}] 被重复注册，后者覆盖前者（展示名: {} -> {}）。"
                            + "请确认不是两个注册器起了同一个名字。",
                    role, tool.name(), previous.displayName(), tool.displayName());
        } else {
            warnIfNameUsedByOtherRole(role, tool);
        }
        roleToolNames.computeIfAbsent(role, k -> new CopyOnWriteArraySet<>()).add(tool.name());

        // 注册期自检：写语义的工具若仍为 READ_ONLY，说明风险等级漏标（会绕过人工确认）
        if (tool.riskLevel() == RiskLevel.READ_ONLY && tool.looksLikeWriteOperation()) {
            log.warn("工具 [{}] 名称疑似写操作，但风险等级为 READ_ONLY，请确认是否应标为 WRITE/DANGEROUS",
                    tool.name());
        }
    }

    /**
     * 同名工具出现在多个角色下时的提示。
     *
     * <p>这是**合法**用法（例如 {@code get_my_courses} 对学生是"我选的课"、对教师是"我教的课"），
     * 所以只记日志不报错；但这类名字一旦被误合并就会产生"数据查错"而不是"报错"，
     * 因此值得在启动日志里留下一条可追溯的记录。
     */
    private void warnIfNameUsedByOtherRole(String role, ToolDefinition tool) {
        for (Map.Entry<String, Map<String, ToolDefinition>> entry : roleTools.entrySet()) {
            if (!entry.getKey().equals(role) && entry.getValue().containsKey(tool.name())) {
                log.info("工具名 [{}] 在角色 [{}] 与 [{}] 下各有独立定义（展示名: {} / {}）——"
                                + "两份定义互不覆盖，模型只会看到自己角色的那一份",
                        tool.name(), entry.getKey(), role,
                        entry.getValue().get(tool.name()).displayName(), tool.displayName());
            }
        }
    }

    /**
     * 按「角色 + 名称」取工具定义。
     *
     * <p><b>必须带角色</b>：全局按名查找正是上面那个覆盖 bug 的入口。
     * 执行工具请用 {@link #executeForRole}（它还额外做白名单校验）。
     */
    public ToolDefinition getTool(String role, String name) {
        Map<String, ToolDefinition> tools = roleTools.get(role);
        return tools == null ? null : tools.get(name);
    }

    public List<ToolDefinition> getToolsByRole(String role) {
        Set<String> names = roleToolNames.get(role);
        Map<String, ToolDefinition> tools = roleTools.get(role);
        if (names == null || names.isEmpty() || tools == null) return List.of();
        return names.stream()
                .map(tools::get)
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
            // 标记"这次改动来自智能助手"，并带上调用者身份：
            // 业务层（如成绩变更日志）据此把 AI 发起的改动与界面操作区分开
            final String result = runAsAi(def, role, args, userId);
            return ToolExecutionResult.ok(result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 异常细节只进日志，不回灌模型（避免泄露 SQL / 表结构 / 堆栈）
            return ToolExecutionResult.failed(e, System.currentTimeMillis() - start);
        }
    }

    /**
     * 在"AI 调用者"上下文中执行工具。
     *
     * <p>工具执行器声明了 {@code throws Exception}，而 {@link ChangeContext#runWith} 接的是
     * {@code Runnable}（不能抛受检异常），因此这里把异常接住再抛给调用方——
     * 语义与原来一致（外层 try/catch 统一处理），只是多了一层上下文包装。
     */
    private static String runAsAi(ToolDefinition def, String role, Map<String, Object> args, String userId)
            throws Exception {
        final String[] result = new String[1];
        final Exception[] failure = new Exception[1];
        ChangeContext.runWith(userId, role, ChangeContext.SOURCE_AI, () -> {
            try {
                result[0] = def.executor().execute(args, userId, role);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return result[0];
    }
}
