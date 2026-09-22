package duyell.ai.tool.declarative;

import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **迁移完整性**测试（M2 计划 1.3 的收口闸门）。
 *
 * <p>批量迁移最典型的失败方式不是"某个工具迁错了"，而是"**少迁了几个而没人发现**"：
 * 手写实现仍在注册、行为照旧、全量测试照旧全绿——半年后再看，代码里有两套写法并存，
 * 谁也不知道哪些是"已迁"、哪些是"漏了"。这类问题只能靠一条**覆盖性**断言来挡：
 * <b>每个角色的工具集合，必须与"该角色的声明式工具组扫出来的集合"完全相等</b>。
 *
 * <p>它顺带钉住了工具面本身（数量与归属）：少一个工具、多一个工具、工具跑到别的角色下，
 * 都会在这里立刻变红，而不是等到用户问"XX 怎么不能用了"。
 *
 * @author duyell
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeclarativeMigrationCoverageTest {

    /** 迁移前的工具面（M1/P1-P5 收口时的基线）：学生 17 / 教师 7 / 管理员 9 */
    private static final List<String> ROLES = List.of("student", "teacher", "admin");
    private static final int MIN_STUDENT_TOOLS = 17;
    private static final int MIN_TEACHER_TOOLS = 7;
    private static final int MIN_ADMIN_TOOLS = 9;

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private DeclarativeToolScanner scanner;

    @Autowired
    private List<DeclarativeToolGroup> groups;

    private Set<String> declarativeNames(String role) {
        return groups.stream()
                .filter(g -> g.role().equals(role))
                .flatMap(g -> scanner.scan(role, g).stream())
                .map(ToolDefinition::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> registeredNames(String role) {
        return registry.getToolsByRole(role).stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 每个角色都必须**全部**由声明式实现接管。
     *
     * <p>失败信息直接列出差集：多出来的名字 = "手写但没迁"；少了的名字 = "声明式里有但没注册进去"。
     */
    @Test
    void everyRegisteredToolIsBackedByADeclarativeGroup() {
        for (String role : ROLES) {
            Set<String> declarative = declarativeNames(role);
            Set<String> registered = registeredNames(role);

            Set<String> notMigratedYet = new TreeSet<>(registered);
            notMigratedYet.removeAll(declarative);
            Set<String> notRegistered = new TreeSet<>(declarative);
            notRegistered.removeAll(registered);

            assertTrue(notMigratedYet.isEmpty(),
                    "角色 [" + role + "] 还有工具没有声明式实现（说明迁移没做完）：" + notMigratedYet);
            assertFalse(notRegistered.isEmpty() && registered.isEmpty(),
                    "角色 [" + role + "] 一个工具都没有，扫描或注册环节断了");
            assertEquals(declarative, registered,
                    "角色 [" + role + "] 的声明式定义与注册结果不一致：未注册=" + notRegistered);
        }
    }

    /** 工具面数量下限：迁移不该顺手弄丢工具 */
    @Test
    void toolSurfaceKeepsItsSize() {
        assertTrue(registeredNames("student").size() >= MIN_STUDENT_TOOLS,
                "学生工具少于基线：" + registeredNames("student"));
        assertTrue(registeredNames("teacher").size() >= MIN_TEACHER_TOOLS,
                "教师工具少于基线：" + registeredNames("teacher"));
        assertTrue(registeredNames("admin").size() >= MIN_ADMIN_TOOLS,
                "管理员工具少于基线：" + registeredNames("admin"));
    }

    /**
     * 每个声明式工具都必须有"给人看的名字"与"能判定风险等级"。
     *
     * <p>这两项是 {@code @ToolMeta} 的存在意义：展示名出现在确认卡片与审计里，
     * 风险等级决定要不要人工确认。缺一个就会静默降级（例如展示成英文方法名）。
     */
    @Test
    void everyDeclarativeToolCarriesDisplayNameAndRiskLevel() {
        for (String role : ROLES) {
            for (DeclarativeToolGroup group : groups) {
                if (!group.role().equals(role)) {
                    continue;
                }
                for (ToolDefinition def : scanner.scan(role, group)) {
                    assertTrue(def.displayName() != null && !def.displayName().isBlank(),
                            def.name() + " 缺少中文展示名（确认卡片/审计会显示成英文名）");
                    assertFalse(def.displayName().equals(def.name()),
                            def.name() + " 的展示名与工具名相同，说明 @ToolMeta 没写或写错了");
                    assertTrue(def.riskLevel() != null, def.name() + " 缺少风险等级");
                    assertTrue(def.executor() != null, def.name() + " 缺少执行器");
                }
            }
        }
    }
}
