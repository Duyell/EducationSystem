package duyell.controller;

import com.duyell.Course;
import com.duyell.CourseSelection;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.JwtUtil;
import utils.Result;

import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@RestController
@RequestMapping("/course-selection")
public class CourseSelectionController {

    private final CourseSelectionMapper courseSelectionMapper;
    private final CourseMapper courseMapper;
    private final JwtUtil jwtUtil;

    @PostMapping("/select/{courseId}")
    public Result<String> select(@PathVariable Integer courseId, HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        courseSelectionMapper.add(courseId, studentId);
        return Result.success("选课成功");
    }

    @DeleteMapping("/{courseId}")
    public Result<String> drop(@PathVariable Integer courseId, HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        courseSelectionMapper.delete(courseId, studentId);
        return Result.success("退课成功");
    }

    @GetMapping("/my")
    public Result<List<Course>> myCourses(HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(studentId);
        List<Integer> courseIds = selections.stream()
                .map(CourseSelection::getCourseId)
                .collect(Collectors.toList());
        List<Course> courses = courseIds.stream()
                .map(id -> courseMapper.selectCourseById(id))
                .collect(Collectors.toList());
        return Result.success(courses);
    }

    @GetMapping("/my-ids")
    public Result<List<Integer>> myCourseIds(HttpServletRequest request) {
        String studentId = getCurrentStudentId(request);
        List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(studentId);
        List<Integer> courseIds = selections.stream()
                .map(CourseSelection::getCourseId)
                .collect(Collectors.toList());
        return Result.success(courseIds);
    }

    private String getCurrentStudentId(HttpServletRequest request) {
        String token = request.getHeader("token");
        return jwtUtil.getUsernameFromToken(token);
    }
}
