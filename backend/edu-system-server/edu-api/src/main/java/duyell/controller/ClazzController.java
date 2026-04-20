package duyell.controller;

import com.duyell.Clazz;
import duyell.service.ClazzService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.parameters.P;
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
                                          @RequestParam(defaultValue = "10") Integer pageSize) {
        PageResult<Clazz> pageResult = clazzService.page(page, pageSize);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Clazz clazz) {
        clazzService.add(clazz);
        return Result.success("添加成功");
    }

    @DeleteMapping
    public Result<String> delete(@RequestParam Integer clazzId) {
        clazzService.delete(clazzId);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Clazz clazz) {
        clazzService.update(clazz);
        return Result.success("更新成功");
    }
}
