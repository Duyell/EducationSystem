package duyell.ai.tool.declarative;

import duyell.ai.tool.RiskLevel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式工具的**项目侧元数据**：Spring AI 的 {@code @Tool} 里没有的那些信息。
 *
 * <p>框架的 {@code @Tool(name, description)} 只描述"工具叫什么、什么时候用"，
 * 而本项目的安全模型还需要两样东西：
 * <ul>
 *   <li>{@code displayName}：中文展示名，出现在**确认卡片**、执行状态与审计里
 *       （模型看到的是英文工具名，用户看到的是这个）；</li>
 *   <li>{@code riskLevel}：决定是否必须人工确认（{@code DANGEROUS} → 弹确认卡片）。</li>
 * </ul>
 *
 * <p><b>刻意不给 {@code riskLevel} 默认值</b>：如果它有默认值，后来新增的写工具
 * 忘记标注就会被当成只读**直接执行**，绕过人工确认——这正是"默认值选错方向"的经典事故。
 * 因此这里强制显式声明，注册期再做一次命名启发式自检（{@code ToolRegistry#register}）。
 *
 * <p>用法：与 {@code @Tool} 标在同一个方法上，见 {@link StudentDeclarativeTools}。
 *
 * @author duyell
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolMeta {

    /** 中文展示名（确认卡片 / 状态提示 / 审计里给人看的名字） */
    String displayName();

    /** 风险等级：DANGEROUS 会在执行前弹出人工确认卡片 */
    RiskLevel riskLevel();
}
