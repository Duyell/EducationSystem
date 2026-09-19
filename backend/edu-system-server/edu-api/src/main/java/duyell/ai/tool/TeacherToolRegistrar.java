package duyell.ai.tool;

import com.duyell.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.*;
import duyell.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
@RequiredArgsConstructor
public class TeacherToolRegistrar implements InitializingBean {

    private final ToolRegistry registry;
    private final CourseMapper courseMapper;
    private final CourseSelectionMapper courseSelectionMapper;
    private final ScoreMapper scoreMapper;
    private final EvaluationMapper evaluationMapper;
    private final StudentMapper studentMapper;
    private final CourseService courseService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterPropertiesSet() {
        registry.register("teacher", new ToolDefinition(
                "get_my_courses", "我的授课课程", "获取当前教师教授的课程列表",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<Course> courses = courseMapper.selectByTeacherId(userId);
                    return objectMapper.writeValueAsString(courses);
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "get_course_students", "课程学生名单", "查看某门课程的所有选课学生",
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
                    // 归属校验：教师仅能查看自己课程的选课名单
                    if (!courseService.isCourseOfTeacher(userId, courseId)) {
                        return "{\"error\":\"无权限查看该课程的选课名单\"}";
                    }
                    List<CourseSelection> selections = courseSelectionMapper.selectByCourseId(courseId);
                    List<String> studentIds = selections.stream()
                            .map(CourseSelection::getStudentId)
                            .toList();
                    if (studentIds.isEmpty()) {
                        return "[]";
                    }
                    List<Student> students = studentMapper.selectByStudentIds(studentIds);
                    return objectMapper.writeValueAsString(students);
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "enter_score", "录入成绩", "录入学生成绩（平时成绩和考试成绩，总成绩=平时×0.4+考试×0.6）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "courseId", Map.of("type", "integer", "description", "课程ID"),
                                "studentId", Map.of("type", "string", "description", "学生学号"),
                                "usualScore", Map.of("type", "number", "description", "平时成绩（可选，默认0）"),
                                "examScore", Map.of("type", "number", "description", "考试成绩（可选，默认0）")
                        ),
                        "required", List.of("courseId", "studentId")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer courseId = Integer.valueOf(args.get("courseId").toString());
                    // 归属校验：教师仅能为自己课程的选课学生录入成绩
                    if (!courseService.isCourseOfTeacher(userId, courseId)) {
                        return "{\"error\":\"无权限为该课程录入成绩\"}";
                    }
                    String studentId = (String) args.get("studentId");
                    BigDecimal usualScore = args.containsKey("usualScore")
                            ? BigDecimal.valueOf(Double.parseDouble(args.get("usualScore").toString()))
                            : BigDecimal.ZERO;
                    BigDecimal examScore = args.containsKey("examScore")
                            ? BigDecimal.valueOf(Double.parseDouble(args.get("examScore").toString()))
                            : BigDecimal.ZERO;
                    // total = usualScore * 0.4 + examScore * 0.6
                    BigDecimal total = usualScore.multiply(BigDecimal.valueOf(0.4))
                            .add(examScore.multiply(BigDecimal.valueOf(0.6)))
                            .setScale(1, RoundingMode.HALF_UP);

                    // 检查是否已存在成绩
                    Score existing = scoreMapper.select(courseId, Integer.valueOf(studentId));
                    if (existing != null) {
                        return "{\"message\":\"该学生此课程已有成绩记录，请使用 update_score 修改\"}";
                    }

                    Score score = new Score();
                    score.setCourseId(courseId);
                    score.setStudentId(studentId);
                    score.setUsualScore(usualScore);
                    score.setExamScore(examScore);
                    score.setTotalScore(total);
                    scoreMapper.add(score);
                    return "{\"message\":\"成绩录入成功\",\"totalScore\":" + total + "}";
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "update_score", "修改成绩", "修改学生已有成绩（总成绩自动重算，提交后影响学业记录）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "integer", "description", "成绩记录ID"),
                                "usualScore", Map.of("type", "number", "description", "平时成绩（可选）"),
                                "examScore", Map.of("type", "number", "description", "考试成绩（可选）")
                        ),
                        "required", List.of("id")
                ),
                RiskLevel.DANGEROUS,
                (args, userId, role) -> {
                    Integer id = Integer.valueOf(args.get("id").toString());

                    Score existing = scoreMapper.selectById(id);
                    if (existing == null) {
                        return "{\"error\":\"成绩记录不存在\"}";
                    }
                    // 归属校验：教师仅能修改自己课程的成绩
                    if (!courseService.isCourseOfTeacher(userId, existing.getCourseId())) {
                        return "{\"error\":\"无权限修改该成绩\"}";
                    }

                    // 合并更新：只传平时成绩时保留原考试成绩，避免被清零
                    BigDecimal usual = existing.getUsualScore() != null ? existing.getUsualScore() : BigDecimal.ZERO;
                    BigDecimal exam = existing.getExamScore() != null ? existing.getExamScore() : BigDecimal.ZERO;
                    if (args.containsKey("usualScore")) {
                        usual = BigDecimal.valueOf(Double.parseDouble(args.get("usualScore").toString()));
                    }
                    if (args.containsKey("examScore")) {
                        exam = BigDecimal.valueOf(Double.parseDouble(args.get("examScore").toString()));
                    }
                    BigDecimal total = usual.multiply(BigDecimal.valueOf(0.4))
                            .add(exam.multiply(BigDecimal.valueOf(0.6)))
                            .setScale(1, RoundingMode.HALF_UP);

                    Score score = new Score();
                    score.setId(id);
                    score.setUsualScore(usual);
                    score.setExamScore(exam);
                    score.setTotalScore(total);
                    scoreMapper.update(score);
                    return "{\"message\":\"成绩修改成功\",\"totalScore\":" + total + "}";
                }
        ));

        registry.register("teacher", new ToolDefinition(
                "get_my_evaluations", "学生评价", "查看学生对当前教师的教学评价",
                noParams(),
                RiskLevel.READ_ONLY,
                (args, userId, role) -> {
                    List<TeacherEvaluation> evaluations = evaluationMapper.list(null, null, userId);
                    return objectMapper.writeValueAsString(evaluations);
                }
        ));
    }

    private Map<String, Object> noParams() {
        return Map.of("type", "object", "properties", Map.of());
    }
}
