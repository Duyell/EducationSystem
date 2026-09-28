package duyell.ai.tool.declarative;

import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把声明式工具按角色注册进 {@link ToolRegistry}。
 *
 * <p><b>它是工具定义的唯一注册者</b>：手写注册器（{@code StudentToolRegistrar} 等）已于 2026-09-28 删除。
 *
 * <h3>为什么是 {@link InitializingBean}（本类改过一次，原因是真踩过坑）</h3>
 * <p>迁移期手写与声明式两套并存时，本类用的是 {@code SmartInitializingSingleton}
 * （在**所有**单例创建完之后才回调），为的是确保"声明式在后、覆盖得掉手写"。
 * 手写删掉之后这个理由消失了，而它留下了一个**更隐蔽**的问题：
 * <b>凡是"在 Bean 创建过程中就把工具清单快照走"的消费者，拿到的都是一张空表</b>。
 * MCP 装配（{@code McpServerConfig#mcpToolSpecifications}）正是这样一处——实测启动日志：
 *
 * <pre>
 *   MCP 工具面已装配: role=student, 开放 14 个   ← 靠手写实现填出来的（当时它先注册）
 *   声明式工具接管完成: role=student, 共 17 个   ← 1.6 秒之后才发生
 * </pre>
 *
 * <p>也就是说：迁移期 MCP 那 14 个工具实际执行的是**手写实现**，而内置 Agent 走的是声明式实现——
 * 同一批工具名在两处跑着两套代码，只因为"谁先注册"由 Bean 创建顺序碰巧决定。
 * 改成 {@code InitializingBean} 后，注册发生在**本 Bean 创建时**；
 * 需要工具清单的消费者必须**显式依赖本类**（见 {@code McpServerConfig} 的形参），
 * 由依赖关系保证顺序，而不是靠运气。
 *
 * <p>为什么不能只依赖"注册得早"：Spring 只保证"你依赖的 Bean 先创建"，不保证"别人比你晚"。
 * 所以每新增一个"启动时快照工具面"的消费者，都必须把本类写进它的依赖里——
 * {@code McpToolFaceAssemblyTest} 就是钉这条不变量的测试。
 *
 * <p><b>批量迁移的用法</b>：新增一个实现 {@link DeclarativeToolGroup} 的 Bean 即可，
 * 本类不需要改——角色与工具的对应关系由各组的 {@code role()} 显式声明。
 *
 * @author duyell
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeclarativeToolRegistrar implements InitializingBean {

    private final ToolRegistry registry;
    private final DeclarativeToolScanner scanner;
    /** 所有声明式工具组（Spring 会把实现该接口的 Bean 全部注入进来） */
    private final List<DeclarativeToolGroup> groups;

    @Override
    public void afterPropertiesSet() {
        if (groups == null || groups.isEmpty()) {
            log.warn("没有任何声明式工具组：检查 {} 的实现类是否被 Spring 扫描到",
                    DeclarativeToolGroup.class.getSimpleName());
            return;
        }
        int total = 0;
        for (DeclarativeToolGroup group : groups) {
            List<String> targetRoles = group.roles();
            // 一个工具组可以服务多个角色（如 M3 的 search_policy 三角色通用）：
            // 每个角色都注册一份**独立的定义**，仍满足"同名工具跨角色互不覆盖"的约定
            for (String role : targetRoles) {
                List<ToolDefinition> definitions = scanner.scan(role, group);
                if (definitions.isEmpty()) {
                    log.warn("声明式工具组 [{}] 在角色 [{}] 下一个工具都没扫到：检查 @Tool 方法是否 public",
                            group.getClass().getSimpleName(), role);
                    continue;
                }
                for (ToolDefinition definition : definitions) {
                    registry.register(role, definition);
                }
                total += definitions.size();
                log.info("声明式工具注册完成: role={}, 共 {} 个 -> {}",
                        role, definitions.size(),
                        definitions.stream().map(ToolDefinition::name).toList());
            }
        }
        log.info("声明式工具注册汇总: {} 个工具组, {} 个工具（按角色计）",
                groups.size(), total);
    }
}
