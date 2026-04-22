package duyell.controller;

import com.duyell.Student;
import duyell.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/student")
public class StudentController {
    private final StudentService studentService;

    @GetMapping
    public Result<PageResult<Student>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String studentName,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) Integer collegeId,
            @RequestParam(required = false) Integer majorId,
            @RequestParam(required = false) Integer clazzId) {
        PageResult<Student> pageResult;
        pageResult = studentService.page(pageNum, pageSize, studentName, studentId, collegeId, majorId, clazzId);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Student student) {
        studentService.add(student);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        studentService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Student student) {
        studentService.update(student);
        return Result.success("更新成功");
    }

}
