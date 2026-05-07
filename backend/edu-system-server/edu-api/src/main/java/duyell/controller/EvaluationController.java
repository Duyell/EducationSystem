package duyell.controller;

import com.duyell.TeacherEvaluation;
import duyell.mapper.CourseMapper;
import duyell.mapper.EvaluationMapper;
import duyell.service.EvaluationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.JwtUtil;
import utils.PageResult;
import utils.Result;

@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluate")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final JwtUtil jwtUtil;
    private final CourseMapper courseMapper;

    @PostMapping
    public Result<String> add(@RequestBody TeacherEvaluation evaluation, HttpServletRequest request) {
        String studentId = getCurrentUsername(request);
        evaluation.setStudentId(studentId);
        evaluationService.add(evaluation);
        return Result.success("评价成功");
    }

    @GetMapping("/my")
    public Result<PageResult<TeacherEvaluation>> myEvaluations(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletRequest request) {
        String studentId = getCurrentUsername(request);
        PageResult<TeacherEvaluation> page = evaluationService.page(pageNum, pageSize, null, studentId, null);
        return Result.success(page);
    }

    @GetMapping("/teacher")
    public Result<PageResult<TeacherEvaluation>> teacherEvaluations(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletRequest request) {
        String teacherId = getCurrentUsername(request);
        PageResult<TeacherEvaluation> page = evaluationService.page(pageNum, pageSize, null, null, teacherId);
        return Result.success(page);
    }

    @GetMapping("/check/{courseId}")
    public Result<TeacherEvaluation> check(@PathVariable Integer courseId, HttpServletRequest request) {
        String studentId = getCurrentUsername(request);
        TeacherEvaluation evaluation = evaluationService.check(courseId, studentId);
        return Result.success(evaluation);
    }

    private String getCurrentUsername(HttpServletRequest request) {
        String token = request.getHeader("token");
        return jwtUtil.getUsernameFromToken(token);
    }
}
