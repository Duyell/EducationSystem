package duyell.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具「门面」治理测试：把几条容易悄悄退化的约定变成硬断言。
 *
 * <p>与 {@code .dsh/eval-p5-tools.cjs} 的分工：那个脚本从**运行中的接口**取工具清单，
 * 用来做跨层评测；本测试跑在 {@code mvn test} 里，不需要起服务与模型，负责守住
 * "每次改工具都必须满足的规矩"。
 *
 * <p>守的四条：
 * <ol>
 *   <li>P5 新增的 11 个工具都在、且挂在正确的角色上（教师的绝不能漏给学生）</li>
 *   <li>写语义的工具不能标成 READ_ONLY —— {@link ToolRegistry} 只在注册时 WARN，
 *       这里升级为失败：漏标风险等级会直接绕过人工确认</li>
 *   <li>有可选参数的工具，描述里必须写明"省略会怎样" —— 这是本项目踩过的坑
 *       （描述不写省略行为，模型会反问而不是直接调用）</li>
 *   <li>每个工具都要有中文展示名与非空描述（确认卡片与审计日志都要用）</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentToolSurfaceTest {

    @Autowired
    private ToolRegistry registry;

    private static final Set<String> P5_STUDENT_TOOLS = Set.of(
            "get_my_training_plan", "audit_my_graduation", "get_my_gpa", "recommend_courses",
            "get_my_exams", "get_selection_status", "list_my_class_times", "check_time_conflict");

    private static final Set<String> P5_TEACHER_TOOLS = Set.of("submit_course_apply", "apply_class_time");

    private static final Set<String> P5_ADMIN_TOOLS = Set.of("approve_course_apply");

    // ---------- 1. 工具都在，且在正确的角色上 ----------

    @Test
    void p5StudentToolsAreRegisteredReadOnly() {
        Map<String, ToolDefinition> tools = byName(registry.getToolsByRole("student"));
        for (String name : P5_STUDENT_TOOLS) {
            ToolDefinition def = tools.get(name);
            assertNotNull(def, "学生工具缺失: " + name);
            assertTrue(def.riskLevel() == RiskLevel.READ_ONLY,
                    name + " 只读查询，应为 READ_ONLY，实际 " + def.riskLevel());
            assertFalse(def.requiresConfirmation(), name + " 是只读工具，不应要求确认");
        }
    }

    @Test
    void p5WriteToolsAreDangerousAndRoleScoped() {
        Map<String, ToolDefinition> teacher = byName(registry.getToolsByRole("teacher"));
        Map<String, ToolDefinition> admin = byName(registry.getToolsByRole("admin"));
        Map<String, ToolDefinition> student = byName(registry.getToolsByRole("student"));

        for (String name : P5_TEACHER_TOOLS) {
            ToolDefinition def = teacher.get(name);
            assertNotNull(def, "教师工具缺失: " + name);
            assertTrue(def.riskLevel() == RiskLevel.DANGEROUS,
                    name + " 会写库，必须 DANGEROUS（需要人工确认），实际 " + def.riskLevel());
            assertTrue(def.requiresConfirmation(), name + " 必须弹确认卡片");
            assertFalse(student.containsKey(name), name + " 不应出现在学生工具里");
            assertFalse(admin.containsKey(name), name + " 不应出现在管理员工具里");
        }

        for (String name : P5_ADMIN_TOOLS) {
            ToolDefinition def = admin.get(name);
            assertNotNull(def, "管理员工具缺失: " + name);
            assertTrue(def.riskLevel() == RiskLevel.DANGEROUS,
                    name + " 会生成开课记录，必须 DANGEROUS，实际 " + def.riskLevel());
            assertFalse(student.containsKey(name), name + " 不应出现在学生工具里");
            assertFalse(teacher.containsKey(name), name + " 不应出现在教师工具里");
        }
    }

    /** 学生专属的读工具绝不能漏给教师/管理员（越权读数据的入口） */
    @Test
    void studentOnlyToolsDoNotLeakToOtherRoles() {
        Map<String, ToolDefinition> teacher = byName(registry.getToolsByRole("teacher"));
        Map<String, ToolDefinition> admin = byName(registry.getToolsByRole("admin"));
        for (String name : P5_STUDENT_TOOLS) {
            assertFalse(teacher.containsKey(name), "学生专属工具漏给了教师: " + name);
            assertFalse(admin.containsKey(name), "学生专属工具漏给了管理员: " + name);
        }
    }

    // ---------- 2. 写语义工具不得标为只读（把注册期的 WARN 升级为失败） ----------

    @Test
    void noWriteSemanticToolIsLeftReadOnly() {
        List<String> offenders = new ArrayList<>();
        for (String role : List.of("student", "teacher", "admin")) {
            for (ToolDefinition def : registry.getToolsByRole(role)) {
                if (looksLikeWrite(def.name()) && def.riskLevel() == RiskLevel.READ_ONLY) {
                    offenders.add(role + "/" + def.name());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下工具名带写语义却标为 READ_ONLY，会绕过人工确认: " + offenders);
    }

    private static boolean looksLikeWrite(String name) {
        String n = name == null ? "" : name.toLowerCase();
        // 注意：不要用裸 "select"，get_selection_status / select_course 的语义相反，混在一起会误报
        return n.contains("insert") || n.contains("update") || n.contains("delete")
                || n.contains("drop_course") || n.contains("enter") || n.contains("evaluate")
                || n.contains("apply") || n.contains("approve") || n.contains("submit")
                || n.contains("select_course");
    }

    // ---------- 3. 有可选参数就必须写明"省略会怎样" ----------

    @Test
    void toolsWithOptionalParamsDocumentWhatOmissionDoes() {
        List<String> offenders = new ArrayList<>();
        for (String role : List.of("student", "teacher", "admin")) {
            for (ToolDefinition def : registry.getToolsByRole(role)) {
                if (countOptionalParams(def) <= 0) {
                    continue;
                }
                if (!mentionsOmission(def)) {
                    offenders.add(role + "/" + def.name() + "(" + countOptionalParams(def) + " 个可选参数)");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下工具存在可选参数，但描述里没说省略时的行为（模型会反问而不是直接调用）: " + offenders);
    }

    @SuppressWarnings("unchecked")
    private static int countOptionalParams(ToolDefinition def) {
        Map<String, Object> params = def.parameters();
        if (params == null) {
            return 0;
        }
        Object propsRaw = params.get("properties");
        if (!(propsRaw instanceof Map<?, ?> props) || props.isEmpty()) {
            return 0;
        }
        Object requiredRaw = params.get("required");
        int required = requiredRaw instanceof List<?> list ? list.size() : 0;
        return Math.max(0, props.size() - required);
    }

    @SuppressWarnings("unchecked")
    private static boolean mentionsOmission(ToolDefinition def) {
        StringBuilder sb = new StringBuilder(def.description() == null ? "" : def.description());
        Map<String, Object> params = def.parameters();
        if (params != null && params.get("properties") instanceof Map<?, ?> props) {
            for (Object v : props.values()) {
                if (v instanceof Map<?, ?> p && p.get("description") != null) {
                    sb.append(' ').append(p.get("description"));
                }
            }
        }
        String text = sb.toString();
        for (String word : List.of("省略", "不传", "留空", "默认", "可空", "不填")) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 4. 展示名与描述非空 ----------

    @Test
    void everyToolHasDisplayNameAndDescription() {
        List<String> offenders = new ArrayList<>();
        for (String role : List.of("student", "teacher", "admin")) {
            List<ToolDefinition> tools = registry.getToolsByRole(role);
            assertFalse(tools.isEmpty(), "角色 " + role + " 一个工具都没有，注册器可能没生效");
            for (ToolDefinition def : tools) {
                if (isBlank(def.displayName()) || isBlank(def.description())) {
                    offenders.add(role + "/" + def.name());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "以下工具缺少中文展示名或描述（确认卡片/审计要用）: " + offenders);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, ToolDefinition> byName(List<ToolDefinition> tools) {
        Map<String, ToolDefinition> map = new java.util.LinkedHashMap<>();
        for (ToolDefinition def : tools) {
            map.put(def.name(), def);
        }
        return map;
    }
}
