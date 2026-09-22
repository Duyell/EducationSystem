package duyell.ai.tool.declarative;

import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把声明式工具按角色**注册（覆盖）**进 {@link ToolRegistry}。
 *
 * <p><b>为什么是 {@link SmartInitializingSingleton} 而不是 {@code InitializingBean}</b>：
 * 手写注册器（{@code StudentToolRegistrar} 等）是在 {@code afterPropertiesSet} 里注册的，
 * 而 Bean 的初始化顺序由依赖关系决定、并不保证"声明式在后"。
 * 如果声明式先跑，就会被手写实现**反过来覆盖**——症状是"迁移做完了但行为一点没变，
 * 只有启动日志里一行 WARN"，非常难查。
 * {@code afterSingletonsInstantiated()} 在**所有**单例创建完成之后才回调，
 * 因此"声明式接管手写"是确定的，与 Bean 顺序无关。
 *
 * <p>覆盖是有意的：手写实现留在代码里作一个版本周期的回归对照（计划 1.4），
 * 但同一时刻只有一个在跑。{@code registerOverride} 会说明"谁接管了谁"，不当作重复注册告警。
 *
 * <p><b>批量迁移的用法</b>：新增一个实现 {@link DeclarativeToolGroup} 的 Bean 即可，
 * 本类不需要改——角色与工具的对应关系由各组的 {@code role()} 显式声明。
 *
 * @author duyell
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeclarativeToolRegistrar implements SmartInitializingSingleton {

    private final ToolRegistry registry;
    private final DeclarativeToolScanner scanner;
    /** 所有声明式工具组（Spring 会把实现该接口的 Bean 全部注入进来） */
    private final List<DeclarativeToolGroup> groups;

    @Override
    public void afterSingletonsInstantiated() {
        if (groups == null || groups.isEmpty()) {
            log.warn("没有任何声明式工具组：检查 {} 的实现类是否被 Spring 扫描到",
                    DeclarativeToolGroup.class.getSimpleName());
            return;
        }
        int total = 0;
        for (DeclarativeToolGroup group : groups) {
            List<ToolDefinition> definitions = scanner.scan(group.role(), group);
            if (definitions.isEmpty()) {
                log.warn("声明式工具组 [{}] 一个工具都没扫到：检查 @Tool 方法是否 public",
                        group.getClass().getSimpleName());
                continue;
            }
            for (ToolDefinition definition : definitions) {
                registry.registerOverride(group.role(), definition);
            }
            total += definitions.size();
            log.info("声明式工具接管完成: role={}, 共 {} 个 -> {}",
                    group.role(), definitions.size(),
                    definitions.stream().map(ToolDefinition::name).toList());
        }
        log.info("声明式工具接管汇总: {} 个工具组, {} 个工具",
                groups.size(), total);
    }
}
