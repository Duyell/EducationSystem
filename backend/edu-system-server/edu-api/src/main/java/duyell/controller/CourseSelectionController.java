package duyell.controller;

import com.duyell.Course;
import duyell.service.CourseSelectionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.JwtUtil;
import utils.Result;

import java.util.List;

/**
 * @author duyell
 * 选课/退课：业务校验与并发控制见 CourseSelectionService
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/course-selection")
public class CourseSelectionController {

    private final CourseSelectionService courseSelectionService;
    private final JwtUtil jwtUtil;

    @PostMapping("/select/{courseId}")
    public Result<String> select(@PathVariable Integer courseId, HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        courseSelectionService.select(courseId, studentId);
        return Result.success("选课成功");
    }

    @DeleteMapping("/{courseId}")
    public Result<String> drop(@PathVariable Integer courseId, HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        courseSelectionService.drop(courseId, studentId);
        return Result.success("退课成功");
    }

    @GetMapping("/my")
    public Result<List<Course>> myCourses(HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        return Result.success(courseSelectionService.listMyCourses(studentId));
    }

    @GetMapping("/my-ids")
    public Result<List<Integer>> myCourseIds(HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        return Result.success(courseSelectionService.listMyCourseIds(studentId));
    }

    private String getCurrentStudentId(HttpServletRequest request) {
        String token = request.getHeader("token");
        return jwtUtil.getUsernameFromToken(token);
    }
}
