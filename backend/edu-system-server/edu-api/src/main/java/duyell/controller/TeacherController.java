package duyell.controller;

import com.duyell.Teacher;
import duyell.service.TeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

import java.util.List;

/**
 * @author duyell
 * 教师管理：学院名称由 SQL JOIN 直接带出（TeacherMapper.list/listAll），无需循环查询
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/teacher")
public class TeacherController {
    private final TeacherService teacherService;

    @GetMapping
    public Result<PageResult<Teacher>> page(
                                    @RequestParam(defaultValue = "1") Integer pageNum,
                                    @RequestParam(defaultValue = "10") Integer pageSize,
                                    @RequestParam(required = false) String teacherName,
                                    @RequestParam(required = false) String teacherId,
                                    @RequestParam(required = false) Integer collegeId,
                                    @RequestParam(required = false) String title) {
        return Result.success(teacherService.page(pageNum, pageSize, teacherName, teacherId, collegeId, title));
    }

    @GetMapping("/all")
    public Result<List<Teacher>> all() {
        return Result.success(teacherService.list());
    }

    @PostMapping
    public Result<String> add(@RequestBody Teacher teacher) {
        teacherService.add(teacher);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        teacherService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Teacher teacher) {
        teacherService.update(teacher);
        return Result.success("更新成功");
    }

}
