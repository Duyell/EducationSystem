package duyell.controller;

import com.duyell.College;
import com.duyell.Teacher;
import duyell.service.TeacherService;
import duyell.service.impl.CollegeServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

import java.util.List;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/teacher")
public class TeacherController {
    private final TeacherService teacherService;
    private final CollegeServiceImpl collegeServiceImpl;

    @GetMapping
    public Result<PageResult<Teacher>> page(
                                    @RequestParam(defaultValue = "1") Integer pageNum,
                                    @RequestParam(defaultValue = "10") Integer pageSize,
                                    @RequestParam(required = false) String teacherName,
                                    @RequestParam(required = false) String teacherId,
                                    @RequestParam(required = false) Integer collegeId,
                                    @RequestParam(required = false) String title) {
        PageResult<Teacher> pageResult = teacherService.page(pageNum, pageSize, teacherName, teacherId,collegeId,title);
        List<Teacher> list = pageResult.getList();
        for(Teacher teacher:list){
            College college = collegeServiceImpl.selectById(teacher.getCollegeId());
            if(college != null){
                teacher.setCollegeName(college.getCollegeName());
            }
        }
        return Result.success(pageResult);
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
