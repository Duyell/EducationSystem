package duyell.controller;

import com.duyell.Score;
import duyell.service.ScoreService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.JwtUtil;
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
    private final JwtUtil jwtUtil;

    @GetMapping
    public Result<PageResult<Score>> page(@RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer pageSize,
                                          @RequestParam(required = false) Integer studentId,
                                          @RequestParam(required = false) Integer courseId,
                                          @RequestParam(required = false) String term) {
        PageResult<Score> pageResult = scoreService.page(page, pageSize, studentId, courseId,term);
        return Result.success(pageResult);
    }

    @GetMapping("/my")
    public Result<PageResult<Score>> myScores(@RequestParam(defaultValue = "1") Integer page,
                                               @RequestParam(defaultValue = "10") Integer pageSize,
                                               HttpServletRequest request) {
        String token = request.getHeader("token");
        String studentId = jwtUtil.getUsernameFromToken(token);
        PageResult<Score> pageResult = scoreService.page(page, pageSize, Integer.valueOf(studentId), null, null);
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
