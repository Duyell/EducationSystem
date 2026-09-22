package duyell.ai.tool.declarative;

import com.duyell.Course;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.mapper.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 教师工具的**声明式实现**（迁移第二批）。
 *
 * <p><b>为什么第二批挑教师的 {@code get_my_courses}</b>：它和学生版**同名**。
 * 2026-09-22 出过一个严重 bug——工具定义存在一张全局表里，后注册的角色覆盖前者，
 * 于是"学生问'我选了什么课'，助手答'你没选任何课'"（见开发记录 (十九) Bug ①）。
 * 现在两个角色都改成了声明式：如果注册器/扫描器按名字去重，那个 bug 会**原样复活**。
 * 因此这一批的价值不在"再多迁一个工具"，而在**证明跨角色同名在声明式路径下同样隔离**。
 *
 * @author duyell
 */
@Component
@RequiredArgsConstructor
public class TeacherDeclarativeTools implements DeclarativeToolGroup {

    private final CourseMapper courseMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String role() {
        return "teacher";
    }

    @Tool(name = "get_my_courses",
            description = "查询**当前登录教师本人**所教授的课程列表（含课程代码、课程名、学期、学分、上课人数上限）。"
                    + "教师问\"我教哪些课\"\"我的授课课程\"时使用；不需要任何参数。"
                    + "注意：本工具只返回该教师自己的课程，教师无权查看他人课程；"
                    + "学生视角的已选课程请由学生账号查询。")
    @ToolMeta(displayName = "我的授课课程", riskLevel = RiskLevel.READ_ONLY)
    public String getMyCourses(ToolContext context) throws Exception {
        String teacherId = DeclarativeToolContext.currentUserId(context);
        List<Course> courses = courseMapper.selectByTeacherId(teacherId);
        // 与手写实现保持同一返回结构（Course 全字段）：模型据此回答课程/学期/学分等问题
        return objectMapper.writeValueAsString(courses);
    }
}
