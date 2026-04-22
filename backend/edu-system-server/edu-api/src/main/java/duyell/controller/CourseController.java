package duyell.controller;

import com.duyell.Course;
import duyell.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/course")
public class CourseController {
    private final CourseService courseService;

    @GetMapping
    public Result<PageResult<Course>> page(@RequestParam(defaultValue = "1") Integer page,
                                           @RequestParam(defaultValue = "10") Integer pageSize,
                                           @RequestParam(required = false) String courseName,
                                           @RequestParam(required = false) String teacherName,
                                           @RequestParam(required = false) String teacherId,
                                           @RequestParam(required = false) Integer collegeId,
                                           @RequestParam(required = false) Integer credit,
                                           @RequestParam(required = false) Integer classHour,
                                           @RequestParam(required = false) Integer maxStudent) {
        PageResult<Course> pageResult = courseService.page(page, pageSize, courseName,teacherName, teacherId, collegeId, credit, classHour, maxStudent);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Course course) {
        courseService.add(course);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        courseService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Course course) {
        courseService.update(course);
        return Result.success("更新成功");
    }
}
