package duyell.ai.tool;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.*;
import duyell.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class AdminToolRegistrar implements InitializingBean {

    private final ToolRegistry registry;
    private final SysUserMapper sysUserMapper;
    private final StudentMapper studentMapper;
    private final TeacherMapper teacherMapper;
    private final CourseMapper courseMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final ClazzMapper clazzMapper;
    private final HomeService homeService;
    private final ObjectMapper objectMapper;

    /** 管理员工具全部为只读查询，因此统一使用 READ_ONLY 风险等级 */
    private static final RiskLevel READ_ONLY = RiskLevel.READ_ONLY;

    @Override
    public void afterPropertiesSet() {
        registry.register("admin", new ToolDefinition(
                "get_statistics", "系统统计", "获取系统统计数据（学生数、教师数、课程数、班级数）",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    Map<String, Object> stats = homeService.getStatistics();
                    return objectMapper.writeValueAsString(stats);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_users", "用户列表",
                "查询系统用户列表。所有筛选参数均可省略，省略时返回全部用户（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "role", Map.of("type", "string",
                                        "enum", List.of("admin", "teacher", "student"),
                                        "description", "按角色筛选；省略则不限制角色"),
                                "username", Map.of("type", "string",
                                        "description", "用户名关键词；省略则不限制")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String roleFilter = (String) args.getOrDefault("role", null);
                    String username = (String) args.getOrDefault("username", null);
                    // 限制最多返回50条
                    List<SysUser> users = sysUserMapper.list(roleFilter, username);
                    if (users.size() > 50) users = users.subList(0, 50);
                    return objectMapper.writeValueAsString(users);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_students", "学生列表",
                "查询学生列表。所有筛选参数均可省略，省略时返回全部学生（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "studentName", Map.of("type", "string",
                                        "description", "学生姓名关键词；省略则不限制"),
                                "studentId", Map.of("type", "string",
                                        "description", "学生学号关键词；省略则不限制")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String studentName = (String) args.getOrDefault("studentName", null);
                    String studentId = (String) args.getOrDefault("studentId", null);
                    List<Student> students = studentMapper.list(studentName, studentId, null, null, null);
                    if (students.size() > 50) students = students.subList(0, 50);
                    return objectMapper.writeValueAsString(students);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_teachers", "教师列表",
                "查询教师列表。所有筛选参数均可省略，省略时返回全部教师（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "teacherName", Map.of("type", "string",
                                        "description", "教师姓名关键词；省略则不限制"),
                                "teacherId", Map.of("type", "string",
                                        "description", "教师工号关键词；省略则不限制")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String teacherName = (String) args.getOrDefault("teacherName", null);
                    String teacherId = (String) args.getOrDefault("teacherId", null);
                    List<Teacher> teachers = teacherMapper.list(teacherName, teacherId, null, null);
                    if (teachers.size() > 50) teachers = teachers.subList(0, 50);
                    return objectMapper.writeValueAsString(teachers);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_courses", "课程列表",
                "查询课程列表。省略 courseName 时返回全部课程（最多50条）。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseName", Map.of("type", "string",
                                        "description", "课程名称关键词；省略则返回全部")
                        )
                ),
                READ_ONLY,
                (args, userId, role) -> {
                    String courseName = (String) args.getOrDefault("courseName", null);
                    List<Course> courses = courseMapper.list(courseName, null, null, null, null, null, null);
                    if (courses.size() > 50) courses = courses.subList(0, 50);
                    return objectMapper.writeValueAsString(courses);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_colleges", "学院列表", "查询所有学院列表",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    List<College> colleges = collegeMapper.list(null);
                    return objectMapper.writeValueAsString(colleges);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_majors", "专业列表", "查询所有专业列表",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    List<Major> majors = majorMapper.list(null, null);
                    return objectMapper.writeValueAsString(majors);
                }
        ));

        registry.register("admin", new ToolDefinition(
                "list_classes", "班级列表", "查询所有班级列表",
                noParams(),
                READ_ONLY,
                (args, userId, role) -> {
                    List<Clazz> classes = clazzMapper.list(null, null, null, null);
                    return objectMapper.writeValueAsString(classes);
                }
        ));
    }

    private Map<String, Object> noParams() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
