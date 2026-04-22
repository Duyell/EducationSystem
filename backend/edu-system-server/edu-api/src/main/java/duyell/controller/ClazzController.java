package duyell.controller;

import com.duyell.Clazz;
import duyell.service.ClazzService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/clazz")
public class ClazzController {
    private final ClazzService clazzService;

    @GetMapping
    public Result<PageResult<Clazz>> page(@RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer pageSize,
                                          @RequestParam(required = false) String clazzName,
                                          @RequestParam(required = false) String grade,
                                          @RequestParam(required = false) Integer majorId,
                                          @RequestParam(required = false) Integer collegeId) {
        PageResult<Clazz> pageResult = clazzService.page(page, pageSize, clazzName, grade, majorId, collegeId);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Clazz clazz) {
        clazzService.add(clazz);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        clazzService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Clazz clazz) {
        clazzService.update(clazz);
        return Result.success("更新成功");
    }
}
