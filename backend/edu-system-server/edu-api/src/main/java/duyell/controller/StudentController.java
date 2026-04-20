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
    public Result<PageResult<Student>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                    @RequestParam(defaultValue = "10") Integer pageSize) {
        PageResult<Student> pageResult;
        pageResult = studentService.page(pageNum, pageSize);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Student student) {
        studentService.add(student);
        return Result.success("添加成功");
    }

    @DeleteMapping
    public Result<String> delete(@RequestParam Integer studentId) {
        studentService.delete(studentId);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Student student) {
        studentService.update(student);
        return Result.success("更新成功");
    }

}
