package duyell.ai.tool;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.*;
import duyell.service.CourseSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Component
@RequiredArgsConstructor
public class StudentToolRegistrar implements InitializingBean {

    private final ToolRegistry registry;
    private final CourseSelectionMapper courseSelectionMapper;
    private final ScoreMapper scoreMapper;
    private final CourseMapper courseMapper;
    private final EvaluationMapper evaluationMapper;
    private final StudentMapper studentMapper;
    private final ObjectMapper objectMapper;
    private final SysUserMapper sysUserMapper;
    private final CourseSelectionService courseSelectionService;

    @Override
    public void afterPropertiesSet() {
        registry.register("student", new ToolDefinition(
                "get_my_courses", "我的已选课程", "获取当前学生已选的课程列表",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(userId);
                    // IN 查询一次取出全部课程，避免 N+1
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
        ));

        registry.register("student", new ToolDefinition(
                "get_my_scores", "我的成绩", "获取当前学生的成绩",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<Score> scores = scoreMapper.list(null, Integer.valueOf(userId), null);
                    return objectMapper.writeValueAsString(scores);
                }
        ));

        registry.register("student", new ToolDefinition(
                "select_course", "选课", "学生选课，添加课程到已选列表",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    // 复用选课服务：事务内完成查重、容量校验与并发控制
                    try {
                        courseSelectionService.select(courseId, userId);
                        return "{\"message\":\"选课成功\"}";
                    } catch (RuntimeException e) {
                        return "{\"message\":\"" + e.getMessage() + "\"}";
                    }
                }
        ));

        registry.register("student", new ToolDefinition(
                "drop_course", "退课", "学生退课，从已选列表中移除课程（可通过重新选课恢复）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                RiskLevel.WRITE,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    courseSelectionMapper.delete(courseId, userId);
                    return "{\"message\":\"退课成功\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "get_course_list", "可选课程列表",
                "查看可选课程列表。不传 courseName 时返回全部可选课程；"
                        + "用户说\"有哪些课能选\"\"列出所有可选课程\"时，直接调用本工具且不要追问课程名称。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseName", Map.of("type", "string",
                                        "description", "课程名称关键词，用于筛选；省略则返回全部")
                        )
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    String courseName = (String) args.getOrDefault("courseName", null);
                    List<Course> courses = courseMapper.list(courseName, null, null, null, null, null, null);
                    return objectMapper.writeValueAsString(courses);
                }
        ));

        registry.register("student", new ToolDefinition(
                "evaluate_teacher", "教学评价", "对某门课程的教师进行教学评价（提交后不可修改）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID"),
                                "teacherId", Map.of("type", "string", "description", "教师工号"),
                                "score", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                                        "description", "评分(1-100)"),
                                "content", Map.of("type", "string", "description", "评价内容（可选）")
                        ),
                        "required", List.of("courseId", "teacherId", "score")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    String teacherId = (String) args.get("teacherId");
                    Integer scoreVal = Integer.valueOf(args.get("score").toString());
                    String content = (String) args.getOrDefault("content", "");

                    TeacherEvaluation eval = new TeacherEvaluation();
                    eval.setCourseId(courseId);
                    eval.setStudentId(userId);
                    eval.setTeacherId(teacherId);
                    eval.setScore(scoreVal);
                    eval.setContent(content);
                    evaluationMapper.add(eval);
                    return "{\"message\":\"评价提交成功\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "check_evaluation", "评价状态检查", "检查某门课程是否已经评价过",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    TeacherEvaluation existing = evaluationMapper.selectByCourseAndStudent(courseId, userId);
                    if (existing != null) {
                        return "{\"evaluated\":true,\"message\":\"该课程已评价\"}";
                    }
                    return "{\"evaluated\":false,\"message\":\"该课程尚未评价\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "get_my_evaluations", "我的评价", "获取当前学生提交的所有教学评价",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<TeacherEvaluation> evaluations = evaluationMapper.list(null, userId, null);
                    return objectMapper.writeValueAsString(evaluations);
                }
        ));
    }

    private Map<String, Object> noParams() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
