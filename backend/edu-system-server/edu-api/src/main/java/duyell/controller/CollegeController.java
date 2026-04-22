package duyell.controller;

import com.duyell.College;
import duyell.service.CollegeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/college")
public class CollegeController {
    private final CollegeService collegeService;

    /**
     * 获取学院列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 学院列表
     */
    @GetMapping
    public Result<PageResult<College>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "10") Integer pageSize,
                                            @RequestParam(required = false) String collegeName) {
        PageResult<College> pageResult = collegeService.page(pageNum, pageSize, collegeName);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody College college) {
        collegeService.add(college);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        collegeService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody College college) {
        collegeService.update(college);
        return Result.success("更新成功");
    }
}
