package duyell.ai.tool.declarative;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code @Tool} 声明式方法**接进本项目既有的安全管线**（M2 计划 1.3）。
 *
 * <p><b>职责边界（迁移的关键取舍）</b>：
 * <pre>
 *   框架提供：工具声明（@Tool/@ToolParam）、参数 JSON Schema 生成、JSON → DTO 绑定与反射调用
 *   本项目保留：角色白名单、参数 Schema 二次校验、危险操作人工确认、审计留痕、风险等级
 * </pre>
 * 也就是说迁移**只替换"怎么写工具"，不替换"谁能调用、能不能直接执行"**。
 * 那四道闸门在 {@code ToolRegistry} / {@code AiChatService} 里，与工具是声明式还是手写无关——
 * 这也是本次迁移最大的风险点，因此专门有测试盯着（见 {@code DeclarativeToolMigrationTest}）。
 *
 * <p>参数 Schema **直接取框架生成的**（{@code ToolDefinition#inputSchema()}），
 * 而不是自己再写一份：一份是"模型看到的参数"，一份是"我们校验用的参数"，
 * 两者一旦漂移，就会出现"校验通过但绑定失败"或反之。同源才不会有这个问题。
 *
 * @author duyell
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeclarativeToolScanner {

    private final ObjectMapper objectMapper;

    /**
     * 扫描一个 Bean 上所有 {@code @Tool} 方法，转换成本项目的 {@link ToolDefinition}。
     *
     * @param role 该 Bean 的工具归属角色（本项目的角色白名单按角色注册）
     * @param bean 携带 {@code @Tool} 方法的 Bean
     */
    public List<ToolDefinition> scan(String role, Object bean) {
        List<ToolDefinition> definitions = new ArrayList<>();
        ReflectionUtils.doWithMethods(bean.getClass(), method -> {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                return;
            }
            definitions.add(toDefinition(role, bean, method, tool));
        }, method -> method.getAnnotation(Tool.class) != null);
        return definitions;
    }

    private ToolDefinition toDefinition(String role, Object bean, Method method, Tool tool) {
        ToolMeta meta = method.getAnnotation(ToolMeta.class);
        if (meta == null) {
            // 宁可启动失败，也不要一个"没有风险等级"的写工具悄悄按只读执行
            throw new IllegalStateException(String.format(
                    "声明式工具 [%s]（%s#%s）缺少 @ToolMeta：必须显式声明展示名与风险等级，"
                            + "否则写操作会被当成只读直接执行、绕过人工确认",
                    tool.name(), bean.getClass().getSimpleName(), method.getName()));
        }

        MethodToolCallback callback = buildCallback(bean, method, tool);
        org.springframework.ai.tool.definition.ToolDefinition frameworkDef = callback.getToolDefinition();
        Map<String, Object> parameters = readSchema(frameworkDef.inputSchema(), tool.name());

        log.info("声明式工具已就绪: role={}, name={}, risk={}, 参数={}",
                role, frameworkDef.name(), meta.riskLevel(), parameters.get("properties"));

        ToolDefinition.ToolExecutor executor = (args, userId, callerRole) -> callback.call(
                objectMapper.writeValueAsString(args == null ? Map.of() : args),
                // userId 通过 ToolContext 传给工具方法（而不是塞进参数 Schema）：
                // 它来自 token，**绝不能**由模型提供；方法若声明了 ToolContext 参数，
                // 框架会要求必须走这个两参重载（否则启动期就报 "ToolContext is required by the method"）
                new ToolContext(Map.of(
                        DeclarativeToolContext.KEY_USER_ID, userId == null ? "" : userId,
                        DeclarativeToolContext.KEY_ROLE, callerRole == null ? "" : callerRole)));

        return new ToolDefinition(frameworkDef.name(), meta.displayName(),
                frameworkDef.description(), parameters, meta.riskLevel(), executor);
    }

    /**
     * 构造框架的回调对象。
     *
     * <p>⚠️ {@code MethodToolCallback.Builder.build()} 会断言 {@code toolDefinition != null}：
     * 它**不会**从 {@code @Tool} 注解自动推导定义（推导是 {@code MethodToolCallbackProvider}
     * 那边做的），只给 {@code toolMethod} + {@code toolObject} 会直接启动失败
     * （实测报 {@code IllegalArgumentException: toolDefinition cannot be null}）。
     * 因此这里显式用框架的 {@code ToolDefinition.builder()} 构造，
     * inputSchema 交给框架的 {@code JsonSchemaGenerator} 生成——Schema 仍然只有框架这一个来源。
     */
    private MethodToolCallback buildCallback(Object bean, Method method, Tool tool) {
        org.springframework.ai.tool.definition.ToolDefinition frameworkDefinition =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .inputSchema(JsonSchemaGenerator.generateForMethodInput(method))
                        .build();
        return MethodToolCallback.builder()
                .toolDefinition(frameworkDefinition)
                .toolMethod(method)
                .toolObject(bean)
                .build();
    }

    /** 框架给出的是 JSON Schema 字符串；本项目内部用 Map 传递（校验器与 payload 都吃 Map） */
    private Map<String, Object> readSchema(String inputSchema, String toolName) {        if (inputSchema == null || inputSchema.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return objectMapper.readValue(inputSchema, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(
                    "声明式工具 [" + toolName + "] 的参数 Schema 解析失败: " + inputSchema, e);
        }
    }
}
