package duyell.ai.tool;

import java.util.Map;

/**
 * 工具参数校验器。
 *
 * <p>职责：在工具真正执行前，按 {@link ToolDefinition#parameters()} 声明的 JSON Schema 校验
 * 模型给出的参数，把「参数错了」变成一条可读的、可自我纠正的错误消息回灌给模型，
 * 而不是让参数错误一路穿到业务代码里变成异常。
 *
 * <p>为什么自研而不用现成库：本项目为离线构建环境，无法引入新依赖
 * （`com.networknt:json-schema-validator` 等不可用）。
 * 因此这里<b>刻意只实现本项目工具实际使用的 Schema 子集</b>：
 * object / string / integer / number / boolean / array + required / enum /
 * minimum / maximum / minLength / maxLength / minItems / maxItems / items。
 * 不冒充完整 JSON Schema 实现。
 */
public interface ToolArgumentValidator {

    /** 校验结果 */
    record Result(boolean valid, String errorMessage) {

        public static Result ok() {
            return new Result(true, null);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    /**
     * 校验参数。
     *
     * @param schema 工具声明的 JSON Schema（{@code type/properties/required/...}）
     * @param args   模型给出的参数（已由 JSON 解析为 Map）
     * @return 校验结果；失败时 {@code errorMessage} 是可回灌给模型的可读原因
     */
    Result validate(Map<String, Object> schema, Map<String, Object> args);
}
