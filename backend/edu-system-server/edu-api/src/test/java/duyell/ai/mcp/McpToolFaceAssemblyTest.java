package duyell.ai.mcp;

import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具面的**装配时机**测试（2026-09-28 由删除手写注册器引出的真实缺陷）。
 *
 * <p><b>它盯的问题不是"暴露策略算得对不对"</b>（那是 {@code McpToolExposureTest} 的事），
 * 而是"装配那一刻，工具注册表里到底有没有东西"：
 * {@code McpServerConfig#mcpToolSpecifications} 是<b>在 Bean 创建过程中</b>把工具清单
 * 快照成 MCP 工具表的（SDK 的 server 一旦 build，工具表就固定），
 * 于是它能不能拿到工具，完全取决于"声明式注册有没有先跑完"。
 *
 * <p>这不是假想：迁移期两套实现并存时，实测启动日志是
 *
 * <pre>
 *   20:31:45.498  MCP 工具面已装配: role=student, 开放 14 个   ← 手写注册器填的（它先注册）
 *   20:31:47.140  声明式工具接管完成: role=student, 共 17 个   ← 1.6 秒后才发生
 * </pre>
 *
 * <p>也就是说当时 MCP 走的是**手写实现**、内置 Agent 走的是**声明式实现**，同一批名字两套代码。
 * 手写删掉后，如果 {@code mcpToolSpecifications} 不显式依赖 {@code DeclarativeToolRegistrar}，
 * 这里读到的就是**空表**——症状是"服务正常启动、MCP 客户端连得上、但一个工具都没有"，
 * 全靠人去翻启动日志。所以这条断言必须存在：**工具面非空，且与注册表里该角色的只读工具完全一致**。
 *
 * <p>为什么用 {@code RANDOM_PORT}：MCP 的装配依赖 Web MVC 的传输层
 * （{@code WebMvcSseServerTransportProvider} 与那个 {@code RouterFunction}），
 * 无 Web 上下文时这些 Bean 建不起来——那样测的就是另一个场景了。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ai.mcp.enabled=true")
class McpToolFaceAssemblyTest {

    /** 与本项目 MCP 默认配置一致（{@code ai.mcp.role=student}、{@code allow-writes=false}） */
    private static final String ROLE = "student";
    private static final int EXPECTED_EXPOSED = 14;

    @Autowired
    private List<McpServerFeatures.SyncToolSpecification> mcpToolSpecifications;

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private ApplicationContext context;

    /**
     * 装配**显式声明**了对工具注册的依赖。
     *
     * <p>为什么这条断言比"工具面非空"更重要：工具面非空只能证明**这一次**顺序碰巧是对的。
     * 本类的作用是钉住"顺序由依赖关系保证"这件事本身——Spring 会先创建
     * {@code declarativeToolRegistrar}（它注册工具），再调用 {@code mcpToolSpecifications} 工厂方法。
     * 一旦有人"顺手"把这个看起来用不到的形参删掉，编译照样通过、今天照样能跑，
     * 而随机性会在某次改动后以"MCP 一个工具都没有"的形式爆出来。这里直接断言依赖边存在。
     *
     * <p><b>这条断言的强度是实测过的</b>（写完专门把形参删掉跑了一遍）：
     * 那条改动让本方法变红，而同一个类里的"工具面非空""危险工具不外放"两条**仍然是绿的**
     * ——因为顺序碰巧还对。也就是说：只断言"结果"抓不住这个缺陷，必须断言"依赖边"。
     * 删除形参时的实际依赖列表：{@code [mcpServerConfig, toolRegistry, aiAuditService]}。
     */
    @Test
    void mcpAssemblyDeclaresItsDependencyOnToolRegistration() {
        String[] dependencies = ((ConfigurableApplicationContext) context).getBeanFactory()
                .getDependenciesForBean("mcpToolSpecifications");

        assertTrue(List.of(dependencies).contains("declarativeToolRegistrar"),
                "mcpToolSpecifications 没有声明对声明式工具注册的依赖：它会在 Bean 创建过程中快照工具表，"
                        + "顺序一旦反过来（MCP 先建、工具后注册）就会装配出**空工具面**。"
                        + "请把 DeclarativeToolRegistrar 加回该 @Bean 方法的形参。实际依赖=" + List.of(dependencies));
    }

    /**
     * 核心断言：MCP 工具表 == 注册表里该角色的只读工具。
     *
     * <p>失败信息特意把两侧都打出来——"空表"与"少了一半"是两种完全不同的病因
     * （前者是装配时机，后者是暴露策略），不该让人再去猜。
     */
    @Test
    void mcpToolFaceIsAssembledFromTheRegisteredTools() {
        assertFalse(mcpToolSpecifications.isEmpty(),
                "MCP 工具面是空的：装配发生在声明式工具注册之前（检查 mcpToolSpecifications 是否依赖 "
                        + "DeclarativeToolRegistrar）");

        Set<String> exposed = mcpToolSpecifications.stream()
                .map(spec -> spec.tool().name())
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> readOnlyInRegistry = registry.getToolsByRole(ROLE).stream()
                .filter(def -> def.riskLevel() == RiskLevel.READ_ONLY)
                .map(ToolDefinition::name)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(readOnlyInRegistry, exposed,
                "MCP 工具面与注册表里该角色的只读工具不一致（左=注册表，右=MCP）："
                        + readOnlyInRegistry + " vs " + exposed);
        assertEquals(EXPECTED_EXPOSED, exposed.size(),
                "MCP 默认开放数量变了（" + EXPECTED_EXPOSED + " -> " + exposed.size() + "）：" + exposed
                        + "。若是故意增删工具，请同步 docs/MCP接入指南.md 里的工具清单与数量。");
    }

    /**
     * 危险工具**永不**外放这条规则，在"装配出来的真实工具表"上再验一次。
     *
     * <p>策略层已有单测（{@code McpToolExposureTest}），这里验的是"策略真的被用在了装配上"——
     * 两者之间隔着一个手工装配过程，而手工装配最容易出的错就是"忘了按策略过滤"。
     */
    @Test
    void dangerousToolsNeverReachTheMcpFace() {
        Set<String> exposed = mcpToolSpecifications.stream()
                .map(spec -> spec.tool().name())
                .collect(Collectors.toSet());

        List<String> dangerous = registry.getToolsByRole(ROLE).stream()
                .filter(def -> def.riskLevel() == RiskLevel.DANGEROUS)
                .map(ToolDefinition::name)
                .toList();

        assertFalse(dangerous.isEmpty(),
                "学生工具面里一个 DANGEROUS 工具都没有？那这条断言就没在验证任何东西，请核对风险等级");
        for (String name : dangerous) {
            assertFalse(exposed.contains(name),
                    "危险工具 [" + name + "] 竟然出现在 MCP 工具面里：它的安全性依赖人工确认（HITL），"
                            + "而 MCP 没有这条通道");
        }
        assertTrue(exposed.contains("get_my_courses"),
                "连只读工具 get_my_courses 都没有，工具面多半是空的或装配错了：" + exposed);
    }
}
