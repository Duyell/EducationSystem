package duyell.ai.tool.declarative;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式工具的**参数取值约束**（框架 {@code @ToolParam} 表达不了的那部分）。
 *
 * <p><b>为什么需要它</b>：Spring AI 1.0.9 的 {@code @ToolParam} 只有 {@code description} 与
 * {@code required}，而本项目手写版工具的 JSON Schema 里还有这些约束：
 * <pre>
 *   get_course_students.courseId    : integer
 *   evaluate_teacher.score          : integer, minimum=1, maximum=5   ← 5 星制
 *   enter_score.usualScore/examScore: number, minimum=0, maximum=100  ← 成绩范围
 *   list_users.role                 : string, enum=[admin,teacher,student]
 *   approve_course_apply.applyId    : integer, minimum=1
 * </pre>
 * 这些**不是装饰**：迁移时若悄悄丢掉，模型就再也看不到"分数只能在 0~100""只能填这三种角色"，
 * 于是开始传越界值——而工具侧要么报错要么落脏数据。`verify-privacy.ps1` 里
 * "enter_score usualScore is bounded 0..100" 这条断言就是专门盯这个的（迁移时曾被它咬红）。
 *
 * <p>用法：标在声明式工具方法的参数上，与 {@code @ToolParam} 并存：
 * <pre>
 *   public String enterScore(
 *           @ToolParam(description = "课程ID", required = true) Integer courseId,
 *           &#64;ParamConstraint(min = 0, max = 100) @ToolParam(description = "平时成绩", required = true) Double usualScore,
 *           ...) { }
 * </pre>
 * 校验合并由 {@code DeclarativeToolScanner} 完成：**在框架生成的 Schema 上追加**
 * enum/minimum/maximum，而不是替换整份 Schema——description/required/类型仍以框架为准
 * （同一份契约只有一个来源）。
 *
 * <p>⚠️ 参数名必须与框架写入 Schema 的属性名一致（即形参名，编译需带 {@code -parameters}，
 * Spring Boot 父 POM 默认开启）。对不上时扫描器**直接启动失败**：约束被静默丢弃的后果
 * 比"服务起不来"严重得多。
 *
 * @author duyell
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ParamConstraint {

    /** 允许的取值（写进 Schema 的 {@code enum}）；空数组表示不限制 */
    String[] options() default {};

    /** 最小值（含）；{@code Long.MIN_VALUE} 表示不限制 */
    long min() default Long.MIN_VALUE;

    /** 最大值（含）；{@code Long.MAX_VALUE} 表示不限制 */
    long max() default Long.MAX_VALUE;
}
