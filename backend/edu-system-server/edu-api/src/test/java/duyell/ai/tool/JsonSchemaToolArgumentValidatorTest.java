package duyell.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具参数 Schema 校验器测试。
 *
 * <p>两个重点：
 * <ol>
 *   <li><b>不能误伤</b>：现有 21 个工具的正常调用必须全部通过（否则等于把好请求挡在门外）；</li>
 *   <li><b>必须拦住</b>：缺必填、类型错、越界、非法枚举都要给出可读原因。</li>
 * </ol>
 */
class JsonSchemaToolArgumentValidatorTest {

    private final JsonSchemaToolArgumentValidator validator =
            new JsonSchemaToolArgumentValidator(new ObjectMapper());

    // ---------- 工具辅助 ----------

    private static Map<String, Object> noParams() {
        return Map.of("type", "object", "properties", Map.of());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    // ---------- 1. 不能误伤现有工具 ----------

    /**
     * 现有 21 个工具的 schema 与一组正常参数的配对。
     * 任何一条校验失败都说明校验器过严，会把正常请求挡掉。
     */
    @Test
    void allExistingToolSchemasAcceptTheirNormalArguments() {
        record Case(String tool, Map<String, Object> schema, Map<String, Object> args) {
        }

        Map<String, Object> courseId = Map.of("courseId", Map.of("type", "integer"));
        Map<String, Object> scoreArgs = Map.of("courseId", Map.of("type", "integer"),
                "studentId", Map.of("type", "string"));

        List<Case> cases = List.of(
                // 学生
                new Case("get_my_courses", noParams(), Map.of()),
                new Case("get_my_scores", noParams(), Map.of()),
                new Case("select_course", objectSchema(courseId, List.of("courseId")), Map.of("courseId", 5)),
                new Case("drop_course", objectSchema(courseId, List.of("courseId")), Map.of("courseId", 5)),
                new Case("get_course_list", objectSchema(
                        Map.of("courseName", Map.of("type", "string")), null), Map.of()),
                new Case("get_course_list(带搜索)", objectSchema(
                        Map.of("courseName", Map.of("type", "string")), null), Map.of("courseName", "数学")),
                new Case("evaluate_teacher", objectSchema(Map.of(
                        "courseId", Map.of("type", "integer"),
                        "teacherId", Map.of("type", "string"),
                        "score", Map.of("type", "integer"),
                        "content", Map.of("type", "string")),
                        List.of("courseId", "teacherId", "score")),
                        Map.of("courseId", 1, "teacherId", "10001", "score", 90, "content", "很好")),
                new Case("check_evaluation", objectSchema(courseId, List.of("courseId")), Map.of("courseId", 1)),
                new Case("get_my_evaluations", noParams(), Map.of()),
                // 教师
                new Case("get_my_courses(teacher)", noParams(), Map.of()),
                new Case("get_course_students", objectSchema(courseId, List.of("courseId")), Map.of("courseId", 3)),
                new Case("enter_score", objectSchema(Map.of(
                        "courseId", Map.of("type", "integer"),
                        "studentId", Map.of("type", "string"),
                        "usualScore", Map.of("type", "number"),
                        "examScore", Map.of("type", "number")),
                        List.of("courseId", "studentId")),
                        Map.of("courseId", 3, "studentId", "2023001", "usualScore", 85.5, "examScore", 90)),
                new Case("enter_score(仅必填)", objectSchema(scoreArgs, List.of("courseId", "studentId")),
                        Map.of("courseId", 3, "studentId", "2023001")),
                new Case("update_score", objectSchema(Map.of(
                        "id", Map.of("type", "integer"),
                        "usualScore", Map.of("type", "number"),
                        "examScore", Map.of("type", "number")),
                        List.of("id")), Map.of("id", 7, "usualScore", 80)),
                new Case("get_my_evaluations(teacher)", noParams(), Map.of()),
                // 管理员
                new Case("get_statistics", noParams(), Map.of()),
                new Case("list_users", objectSchema(Map.of(
                        "role", Map.of("type", "string"),
                        "username", Map.of("type", "string")), null),
                        Map.of("role", "student", "username", "2023")),
                new Case("list_students", objectSchema(Map.of(
                        "studentName", Map.of("type", "string"),
                        "studentId", Map.of("type", "string")), null), Map.of()),
                new Case("list_teachers", objectSchema(Map.of(
                        "teacherName", Map.of("type", "string"),
                        "teacherId", Map.of("type", "string")), null), Map.of("teacherId", "10001")),
                new Case("list_courses", objectSchema(
                        Map.of("courseName", Map.of("type", "string")), null), Map.of("courseName", "英语")),
                new Case("list_colleges", noParams(), Map.of()),
                new Case("list_majors", noParams(), Map.of()),
                new Case("list_classes", noParams(), Map.of())
        );

        for (Case c : cases) {
            ToolArgumentValidator.Result r = validator.validate(c.schema(), c.args());
            assertTrue(r.valid(), "正常调用被误判为非法: " + c.tool() + " -> " + r.errorMessage());
        }
    }

    // ---------- 2. 必须拦住的问题 ----------

    @Test
    void missingRequiredArgumentIsReported() {
        Map<String, Object> schema = objectSchema(Map.of("courseId", Map.of("type", "integer")),
                List.of("courseId"));

        ToolArgumentValidator.Result r = validator.validate(schema, Map.of());

        assertFalse(r.valid());
        assertTrue(r.errorMessage().contains("courseId"), r.errorMessage());
        assertTrue(r.errorMessage().contains("必填"), r.errorMessage());
    }

    @Test
    void typeMismatchIsReported() {
        Map<String, Object> schema = objectSchema(Map.of("courseId", Map.of("type", "integer")),
                List.of("courseId"));

        ToolArgumentValidator.Result r = validator.validate(schema, Map.of("courseId", "五"));

        assertFalse(r.valid());
        assertTrue(r.errorMessage().contains("integer"), r.errorMessage());
    }

    @Test
    void integerTypeRejectsDecimal() {
        Map<String, Object> schema = objectSchema(Map.of("courseId", Map.of("type", "integer")),
                List.of("courseId"));

        assertFalse(validator.validate(schema, Map.of("courseId", 5.5)).valid(),
                "整数参数不应接受小数");
    }

    @Test
    void numberTypeAcceptsIntegerAndDecimal() {
        Map<String, Object> schema = objectSchema(Map.of("usualScore", Map.of("type", "number")), null);

        assertTrue(validator.validate(schema, Map.of("usualScore", 85)).valid());
        assertTrue(validator.validate(schema, Map.of("usualScore", 85.5)).valid());
    }

    @Test
    void numericBoundsAreEnforced() {
        Map<String, Object> schema = objectSchema(Map.of(
                "score", Map.of("type", "integer", "minimum", 1, "maximum", 100)), null);

        assertTrue(validator.validate(schema, Map.of("score", 1)).valid(), "下边界应通过");
        assertTrue(validator.validate(schema, Map.of("score", 100)).valid(), "上边界应通过");

        ToolArgumentValidator.Result low = validator.validate(schema, Map.of("score", 0));
        assertFalse(low.valid());
        assertTrue(low.errorMessage().contains("不能小于"), low.errorMessage());

        ToolArgumentValidator.Result high = validator.validate(schema, Map.of("score", 101));
        assertFalse(high.valid());
        assertTrue(high.errorMessage().contains("不能大于"), high.errorMessage());
    }

    @Test
    void enumConstraintIsEnforced() {
        Map<String, Object> schema = objectSchema(Map.of(
                "role", Map.of("type", "string", "enum", List.of("admin", "teacher", "student"))), null);

        assertTrue(validator.validate(schema, Map.of("role", "student")).valid());

        ToolArgumentValidator.Result r = validator.validate(schema, Map.of("role", "principal"));
        assertFalse(r.valid());
        assertTrue(r.errorMessage().contains("必须是"), r.errorMessage());
    }

    @Test
    void stringLengthBoundsAreEnforced() {
        Map<String, Object> schema = objectSchema(Map.of(
                "content", Map.of("type", "string", "maxLength", 5)), null);

        assertTrue(validator.validate(schema, Map.of("content", "12345")).valid());

        ToolArgumentValidator.Result r = validator.validate(schema, Map.of("content", "123456"));
        assertFalse(r.valid());
        assertTrue(r.errorMessage().contains("不能超过"), r.errorMessage());
    }

    @Test
    void booleanAndArrayTypesAreSupported() {
        Map<String, Object> schema = objectSchema(Map.of(
                "flag", Map.of("type", "boolean"),
                "ids", Map.of("type", "array", "items", Map.of("type", "integer"), "minItems", 1)), null);

        assertTrue(validator.validate(schema, Map.of("flag", true, "ids", List.of(1, 2))).valid());

        assertFalse(validator.validate(schema, Map.of("flag", "yes")).valid(), "布尔类型应拒绝字符串");
        assertFalse(validator.validate(schema, Map.of("ids", List.of())).valid(), "minItems 应生效");
        assertFalse(validator.validate(schema, Map.of("ids", List.of("a"))).valid(), "数组元素类型应生效");
    }

    @Test
    void allProblemsAreCollectedInOnePass() {
        Map<String, Object> schema = objectSchema(Map.of(
                "courseId", Map.of("type", "integer"),
                "score", Map.of("type", "integer", "maximum", 100)),
                List.of("courseId", "studentId"));

        // 缺 studentId + courseId 类型错 + score 越界，应一次性全部报出
        ToolArgumentValidator.Result r = validator.validate(schema,
                Map.of("courseId", "x", "score", 200));

        assertFalse(r.valid());
        assertTrue(r.errorMessage().contains("studentId"), r.errorMessage());
        assertTrue(r.errorMessage().contains("courseId"), r.errorMessage());
        assertTrue(r.errorMessage().contains("score"), r.errorMessage());
    }

    // ---------- 3. 边界与稳健性 ----------

    @Test
    void nullOrDefaultSchemasAreTreatedAsUnconstrained() {
        assertTrue(validator.validate(null, Map.of("anything", 1)).valid(),
                "无 schema 的工具不应被阻断");
        assertTrue(validator.validate(Map.of(), Map.of("anything", 1)).valid());
        assertTrue(validator.validate(noParams(), Map.of()).valid());
    }

    @Test
    void extraUndeclaredArgumentIsReportedButDoesNotSilentlyPass() {
        Map<String, Object> schema = objectSchema(Map.of("courseId", Map.of("type", "integer")),
                List.of("courseId"));

        ToolArgumentValidator.Result r = validator.validate(schema,
                Map.of("courseId", 1, "unexpected", "x"));

        assertFalse(r.valid(), "未声明参数应被报告，而不是静默忽略");
        assertTrue(r.errorMessage().contains("unexpected"), r.errorMessage());
    }

    @Test
    void objectTypeMismatchIsReported() {
        Map<String, Object> schema = objectSchema(Map.of("nested", Map.of("type", "object")), null);

        ToolArgumentValidator.Result r = validator.validate(schema, Map.of("nested", "不是对象"));

        assertFalse(r.valid());
        assertTrue(r.errorMessage().contains("对象"), r.errorMessage());
    }
}
