package duyell.ai.tool;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.*;
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

    @Override
    public void afterPropertiesSet() {
        registry.register("student", new ToolDefinition(
                "get_my_courses", "获取当前学生已选的课程列表",
                noParams(),
                (args, userId, role) -> {
                    List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(userId);
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (CourseSelection sel : selections) {
                        Course course = courseMapper.selectCourseById(sel.getCourseId());
                        if (course != null) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("courseId", course.getId());
                            item.put("courseName", course.getCourseName());
                            item.put("teacherName", course.getTeacherName());
                            item.put("term", course.getTerm());
                            item.put("credit", course.getCredit());
                            result.add(item);
                        }
                    }
                    return objectMapper.writeValueAsString(result);
                }
        ));

        registry.register("student", new ToolDefinition(
                "get_my_scores", "获取当前学生的成绩",
                noParams(),
                (args, userId, role) -> {
                    List<Score> scores = scoreMapper.list(null, Integer.valueOf(userId), null);
                    return objectMapper.writeValueAsString(scores);
                }
        ));

        registry.register("student", new ToolDefinition(
                "select_course", "学生选课，添加课程到已选列表",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    // 检查是否已选
                    List<CourseSelection> existing = courseSelectionMapper.selectByStudentId(userId);
                    boolean alreadySelected = existing.stream().anyMatch(s -> s.getCourseId().equals(courseId));
                    if (alreadySelected) {
                        return "{\"message\":\"该课程已选择，请勿重复选课\"}";
                    }
                    // 检查课程容量
                    Course course = courseMapper.selectCourseById(courseId);
                    if (course != null && course.getMaxStudent() != null) {
                        List<CourseSelection> selections = courseSelectionMapper.selectByCourseId(courseId);
                        if (selections.size() >= course.getMaxStudent()) {
                            return "{\"message\":\"该课程名额已满\"}";
                        }
                    }
                    courseSelectionMapper.add(courseId, userId);
                    return "{\"message\":\"选课成功\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "drop_course", "学生退课，从已选列表中移除课程",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    courseSelectionMapper.delete(courseId, userId);
                    return "{\"message\":\"退课成功\"}";
                }
        ));

        registry.register("student", new ToolDefinition(
                "get_course_list", "查看所有可选课程的列表（支持按名称搜索）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseName", Map.of("type", "string", "description", "课程名称（可选，用于搜索）")
                        )
                ),
                (args, userId, role) -> {
                    String courseName = (String) args.getOrDefault("courseName", null);
                    List<Course> courses = courseMapper.list(courseName, null, null, null, null, null, null);
                    return objectMapper.writeValueAsString(courses);
                }
        ));

        registry.register("student", new ToolDefinition(
                "evaluate_teacher", "对某门课程的教师进行教学评价",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID"),
                                "teacherId", Map.of("type", "string", "description", "教师工号"),
                                "score", Map.of("type", "integer", "description", "评分(1-100)"),
                                "content", Map.of("type", "string", "description", "评价内容（可选）")
                        ),
                        "required", List.of("courseId", "teacherId", "score")
                ),
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
                "check_evaluation", "检查某门课程是否已经评价过",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID")
                        ),
                        "required", List.of("courseId")
                ),
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
                "get_my_evaluations", "获取当前学生提交的所有教学评价",
                noParams(),
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
