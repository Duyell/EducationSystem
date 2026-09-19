package duyell.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ToolArgumentValidator} 的默认实现：基于 Jackson 的轻量 Schema 子集校验。
 *
 * <p>覆盖的关键字见接口注释。逐字段收集<b>全部</b>问题后一次性回灌，
 * 让模型一轮就能把参数改对，而不是挤牙膏式地一次报一个。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonSchemaToolArgumentValidator implements ToolArgumentValidator {

    private final ObjectMapper objectMapper;

    @Override
    public Result validate(Map<String, Object> schema, Map<String, Object> args) {
        if (schema == null || schema.isEmpty()) {
            return Result.ok();
        }
        try {
            JsonNode schemaNode = objectMapper.valueToTree(schema);
            JsonNode argsNode = objectMapper.valueToTree(args == null ? Map.of() : args);

            List<String> errors = new ArrayList<>();
            validateNode(schemaNode, argsNode, "参数", errors);

            if (errors.isEmpty()) {
                return Result.ok();
            }
            return Result.fail(String.join("；", errors));
        } catch (Exception e) {
            // 校验器自身出错时放行：不能因为校验实现的问题阻断工具执行。
            // 但要告警，否则等于校验静默失效。
            log.error("工具参数校验器异常，已放行本次调用（请检查 schema 定义）", e);
            return Result.ok();
        }
    }

    private void validateNode(JsonNode schema, JsonNode value, String path, List<String> errors) {
        String type = text(schema, "type");

        if ("object".equals(type)) {
            validateObject(schema, value, path, errors);
            return;
        }
        // 非 object 类型：值缺失时无法判断是「未提供」还是「显式 null」，
        // 缺失由父级 required 负责报错，这里只在值存在时校验。
        if (value == null || value.isNull() || value.isMissingNode()) {
            return;
        }
        validateLeaf(schema, value, path, type, errors);
    }

    private void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            errors.add(path + " 缺失");
            return;
        }
        if (!value.isObject()) {
            errors.add(path + " 应为对象，实际为 " + describe(value));
            return;
        }

        // 1) 必填
        for (JsonNode req : arrayOf(schema.get("required"))) {
            String field = req.asText();
            JsonNode fieldValue = value.get(field);
            if (fieldValue == null || fieldValue.isNull() || fieldValue.isMissingNode()) {
                errors.add("缺少必填参数 " + childPath(path, field));
            }
        }

        // 2) 逐字段校验
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            properties.properties().forEach(entry -> {
                String field = entry.getKey();
                JsonNode fieldValue = value.get(field);
                if (fieldValue != null && !fieldValue.isNull()) {
                    validateNode(entry.getValue(), fieldValue, childPath(path, field), errors);
                }
            });
        }

        // 3) 未声明的多余参数：提示但不阻断（模型偶尔会带上无关字段，不值得让整次调用失败）
        if (properties != null && properties.isObject()) {
            Set<String> declared = new LinkedHashSet<>();
            properties.fieldNames().forEachRemaining(declared::add);
            value.fieldNames().forEachRemaining(field -> {
                if (!declared.contains(field)) {
                    errors.add("存在未声明的参数 " + childPath(path, field));
                }
            });
        }
    }

    private void validateLeaf(JsonNode schema, JsonNode value, String path,
                              String type, List<String> errors) {
        if (type != null && !typeMatches(type, value)) {
            errors.add(path + " 应为 " + type + "，实际为 " + describe(value));
            return; // 类型都不对，后续约束无意义
        }

        // enum
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray() && enumNode.size() > 0) {
            boolean matched = false;
            for (JsonNode allowed : enumNode) {
                if (allowed.equals(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + " 取值必须是 " + enumNode.toString() + " 之一，实际为 " + value);
            }
        }

        // 数值区间
        if (value.isNumber()) {
            double actual = value.asDouble();
            if (schema.has("minimum") && actual < schema.get("minimum").asDouble()) {
                errors.add(path + " 不能小于 " + schema.get("minimum").asDouble() + "，实际为 " + actual);
            }
            if (schema.has("maximum") && actual > schema.get("maximum").asDouble()) {
                errors.add(path + " 不能大于 " + schema.get("maximum").asDouble() + "，实际为 " + actual);
            }
        }

        // 字符串长度
        if (value.isTextual()) {
            int len = value.asText().length();
            if (schema.has("minLength") && len < schema.get("minLength").asInt()) {
                errors.add(path + " 长度不能少于 " + schema.get("minLength").asInt() + " 个字符");
            }
            if (schema.has("maxLength") && len > schema.get("maxLength").asInt()) {
                errors.add(path + " 长度不能超过 " + schema.get("maxLength").asInt() + " 个字符");
            }
        }

        // 数组：元素与长度
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errors.add(path + " 至少需要 " + schema.get("minItems").asInt() + " 项");
            }
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                errors.add(path + " 最多允许 " + schema.get("maxItems").asInt() + " 项");
            }
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                for (int i = 0; i < value.size(); i++) {
                    validateNode(items, value.get(i), path + "[" + i + "]", errors);
                }
            }
        }
    }

    /** 类型匹配。integer 接受 JSON 整数；number 同时接受整数与小数 */
    private boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true; // 未知类型声明不阻断，交由工具自身处理
        };
    }

    private String describe(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isTextual()) {
            return "字符串";
        }
        if (value.isNumber()) {
            return "数字";
        }
        if (value.isBoolean()) {
            return "布尔值";
        }
        if (value.isArray()) {
            return "数组";
        }
        if (value.isObject()) {
            return "对象";
        }
        return value.getNodeType().toString();
    }

    private String childPath(String path, String field) {
        return path + "." + field;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private Iterable<JsonNode> arrayOf(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
    }
}
