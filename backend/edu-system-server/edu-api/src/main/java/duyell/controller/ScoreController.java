package duyell.controller;

import com.duyell.Score;
import duyell.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/score")
public class ScoreController {
    private final ScoreService scoreService;

    @GetMapping
    public Result<PageResult<Score>> page(@RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer pageSize,
                                          @RequestParam(required = false) Integer studentId,
                                          @RequestParam(required = false) Integer courseId,
                                          @RequestParam(required = false) String term) {
        PageResult<Score> pageResult = scoreService.page(page, pageSize, studentId, courseId,term);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody Score score) {
        scoreService.add(score);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        scoreService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Score score) {
        scoreService.update(score);
        return Result.success("更新成功");
    }
}
