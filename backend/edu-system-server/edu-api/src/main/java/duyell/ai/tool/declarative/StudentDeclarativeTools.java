package duyell.ai.tool.declarative;

import com.duyell.Course;
import com.duyell.CourseSelection;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.service.CourseSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学生工具的**声明式实现**（M2 计划 1.3 的迁移试点）。
 *
 * <p><b>为什么先迁这两个</b>：
 * <ul>
 *   <li>{@code get_my_courses} —— 最典型的只读查询（无参数、无副作用），先确认链路走得通；</li>
 *   <li>{@code select_course} —— 最典型的**危险写操作**（要弹确认卡片、要落审计）。
 *       只迁只读工具等于没验证迁移的安全性：真正的风险是"换了声明方式之后，
 *       确认卡片与审计还在不在"。两个一起迁，才覆盖了迁移的两端。</li>
 * </ul>
 *
 * <p><b>与手写实现的关系</b>：手写版本（{@code StudentToolRegistrar} 里）**保留不动**，
 * 由 {@code DeclarativeToolRegistrar} 在启动完成后用声明式版本覆盖注册
 * （计划 1.4 的要求：旧实现留一个版本周期作回归对照）。
 * 因此这两个工具目前是"声明式在跑、手写留档"，对照与回退都是一行注册的事。
 *
 * <p><b>方法签名约定</b>：
 * <ul>
 *   <li>参数用 record/DTO 承载，框架据此生成 JSON Schema（{@code @ToolParam} 写描述与是否必填）；</li>
 *   <li>需要知道"谁在问"时声明一个 {@link ToolContext} 参数——它**不会**出现在参数 Schema 里，
 *       而是由 {@code DeclarativeToolScanner} 从 token 侧的调用链注入（见 {@link DeclarativeToolContext}）；</li>
 *   <li>返回值是给模型看的 JSON 字符串（本项目一贯口径：模型读 JSON，不读 Java 对象）。</li>
 * </ul>
 *
 * <p>⚠️ 描述文字刻意比手写版更明确（"何时用/何时不用"）：这是 1.3 的目的之一——
 * 工具描述是模型选路的唯一依据之一。但也因此，迁移前后的**选路表现不具备严格可比性**，
 * 对照评测要看的是"该调的工具还会不会被调用"，而不是逐字一致。
 *
 * @author duyell
 */
@Component
@RequiredArgsConstructor
public class StudentDeclarativeTools {

    private final CourseSelectionMapper courseSelectionMapper;
    private final CourseMapper courseMapper;
    private final CourseSelectionService courseSelectionService;
    private final ObjectMapper objectMapper;

    @Tool(name = "get_my_courses",
            description = "查询**当前登录学生本人**已选的课程列表（含课程名、授课教师、学期、学分）。"
                    + "学生问\"我选了什么课\"\"我的课表上有哪些课\"\"我这学期修了哪些课\"时使用。"
                    + "只返回本人数据；不需要任何参数。"
                    + "如果学生问的是\"有哪些课可以选\"，请改用 get_course_list。")
    @ToolMeta(displayName = "我的已选课程", riskLevel = RiskLevel.READ_ONLY)
    public String getMyCourses(ToolContext context) throws Exception {
        String studentId = DeclarativeToolContext.currentUserId(context);

        List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(studentId);
        // IN 查询一次取出全部课程，避免 N+1（与手写实现同一口径）
        List<Integer> courseIds = selections.stream()
                .map(CourseSelection::getCourseId)
                .toList();
        List<Course> courses = courseIds.isEmpty() ? List.of() : courseMapper.selectByIds(courseIds);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Course course : courses) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseId", course.getId());
            item.put("courseName", course.getCourseName());
            item.put("teacherName", course.getTeacherName());
            item.put("term", course.getTerm());
            item.put("credit", course.getCredit());
            result.add(item);
        }
        return objectMapper.writeValueAsString(result);
    }

    /**
     * 选课（危险操作，执行前会弹确认卡片）。
     *
     * <p>⚠️ 参数刻意写成**平铺的 {@code @ToolParam} 形参**，而不用一个 DTO record 包起来：
     * 框架的 {@code JsonSchemaGenerator} 对"单个对象参数"会**再套一层参数名**，生成的 Schema 是
     * <pre>{"properties":{"request":{"properties":{"courseId":...},"required":["courseId"]}}}</pre>
     * 也就是模型得写成 {@code {"request":{"courseId":5}}}。而手写实现的契约是平铺的
     * {@code {"courseId":5}}——迁移如果悄悄换了模型要填的形状，就是一次**静默的契约破坏**
     * （模型会开始传错参数，而所有编译期检查都不会报错）。
     * 这个形状差异是 {@code DeclarativeToolMigrationTest} 断言 {@code properties} 里有 {@code courseId} 时抓到的。
     */
    @Tool(name = "select_course",
            description = "为**当前登录学生本人**选一门课。"
                    + "学生明确说\"我要选某门课\"\"帮我选上XX课\"且已经知道课程ID时使用。"
                    + "选课会写入数据，因此系统会先弹出确认卡片，用户确认后才真正执行。"
                    + "如果学生还没确定选哪门课，请先用 get_course_list 给出可选课程，不要凭空猜 courseId。")
    @ToolMeta(displayName = "选课", riskLevel = RiskLevel.DANGEROUS)
    public String selectCourse(
            @ToolParam(description = "课程ID（来自可选课程列表里的 courseId，不是课程代码）", required = true)
            Integer courseId,
            ToolContext context) {
        String studentId = DeclarativeToolContext.currentUserId(context);
        if (courseId == null) {
            return "{\"message\":\"缺少课程ID，请先确认要选哪门课\"}";
        }
        try {
            // 复用选课服务：事务内完成查重、容量校验、时间冲突与并发控制
            courseSelectionService.select(courseId, studentId);
            return "{\"message\":\"选课成功\"}";
        } catch (RuntimeException e) {
            // 业务原因（已修过/时间冲突/名额已满/未开放…）如实转述给模型，由它解释给用户
            return "{\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
