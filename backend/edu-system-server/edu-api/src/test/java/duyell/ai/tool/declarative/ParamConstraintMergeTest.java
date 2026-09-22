package duyell.ai.tool.declarative;

import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ParamConstraint} 合并逻辑的单测（纯逻辑，不依赖 Spring / 数据库）。
 *
 * <p>它守的是"迁移不能悄悄削弱参数契约"：框架的 {@code @ToolParam} 没有
 * enum/minimum/maximum，若不在生成的 Schema 上补回来，模型就再也看不到
 * "分数 0~100""角色三选一"这类边界。{@code .dsh/verify-privacy.ps1} 里
 * "enter_score usualScore is bounded 0..100" 就是这条链路的端到端断言。
 */
class ParamConstraintMergeTest {

    private final DeclarativeToolScanner scanner = new DeclarativeToolScanner(new ObjectMapper());

    /** 正常用法：约束被追加到框架生成的 Schema 上 */
    static class Demo {
        @Tool(name = "demo_tool", description = "演示")
        @ToolMeta(displayName = "演示工具", riskLevel = RiskLevel.READ_ONLY)
        public String demo(
                @ToolParam(description = "分数", required = true)
                @ParamConstraint(min = 0, max = 100) Double score,
                @ToolParam(description = "类型", required = false)
                @ParamConstraint(options = {"admin", "teacher", "student"}) String kind) {
            return "{}";
        }
    }

    /** 误用：给 ToolContext 参数加约束——它根本不在 Schema 里，必须启动期就报错 */
    static class Misused {
        @Tool(name = "misused_tool", description = "误用")
        @ToolMeta(displayName = "误用工具", riskLevel = RiskLevel.READ_ONLY)
        public String misused(@ParamConstraint(min = 1) ToolContext context) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propertiesOf(ToolDefinition def) {
        return (Map<String, Object>) def.parameters().get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propertyOf(ToolDefinition def, String name) {
        return (Map<String, Object>) propertiesOf(def).get(name);
    }

    @Test
    void constraintsAreMergedIntoTheGeneratedSchema() {
        ToolDefinition def = scanner.scan("student", new Demo()).get(0);

        Map<String, Object> score = propertyOf(def, "score");
        assertEquals(0, ((Number) score.get("minimum")).intValue(), "minimum 必须补回 Schema：" + score);
        assertEquals(100, ((Number) score.get("maximum")).intValue(), "maximum 必须补回 Schema：" + score);

        Map<String, Object> kind = propertyOf(def, "kind");
        assertEquals(List.of("admin", "teacher", "student"), kind.get("enum"),
                "enum 必须补回 Schema：" + kind);

        // 框架那部分（description/required/类型）不能被覆盖掉
        assertTrue(String.valueOf(score.get("description")).contains("分数"), "description 仍在：" + score);
        assertEquals(List.of("score"), def.parameters().get("required"), "required 仍以框架为准");
    }

    @Test
    void constraintOnANonSchemaParameterFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> scanner.scan("student", new Misused()),
                "约束对不上属性名时必须启动失败，而不是静默丢弃");
        assertTrue(e.getMessage().contains("misused_tool"), e.getMessage());
        assertTrue(e.getMessage().contains("-parameters"), "报错要指出可能原因：" + e.getMessage());
    }
}
