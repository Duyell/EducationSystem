package duyell.controller;

import com.duyell.Major;
import duyell.service.MajorService;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/major")
public class MajorController {
    private final MajorService majorService;

    @GetMapping
    public Result<PageResult<Major>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String majorName,
            @RequestParam(required = false) Integer collegeId) {
        PageResult<Major> pageResult = majorService.page(pageNum, pageSize, collegeId, majorName);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Major major) {
        majorService.add(major);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        majorService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Major major) {
        majorService.update(major);
        return Result.success("更新成功");
    }
}
