package duyell.controller;

import com.duyell.Teacher;
import duyell.service.TeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/teacher")
public class TeacherController {
    private final TeacherService teacherService;

    @GetMapping
    public Result<PageResult<Teacher>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                    @RequestParam(defaultValue = "10") Integer pageSize) {
        PageResult<Teacher> pageResult = teacherService.page(pageNum, pageSize);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Teacher teacher) {
        teacherService.add(teacher);
        return Result.success("添加成功");
    }

    @DeleteMapping
    public Result<String> delete(@RequestParam Integer teacherId) {
        teacherService.delete(teacherId);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Teacher teacher) {
        teacherService.update(teacher);
        return Result.success("更新成功");
    }

}
