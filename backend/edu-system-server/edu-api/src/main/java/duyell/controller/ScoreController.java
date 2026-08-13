package duyell.controller;

import com.duyell.Score;
import duyell.service.CourseService;
import duyell.service.ScoreService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.JwtUtil;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 * 成绩管理：管理员可管理全部成绩；教师仅能操作自己课程的分数（归属校验）
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/score")
public class ScoreController {
    private final ScoreService scoreService;
    private final CourseService courseService;
    private final JwtUtil jwtUtil;

    @GetMapping
    public Result<PageResult<Score>> page(@RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer pageSize,
                                          @RequestParam(required = false) Integer studentId,
                                          @RequestParam(required = false) Integer courseId,
                                          @RequestParam(required = false) String term,
                                          HttpServletRequest request) {
        // 教师查询必须指定课程且课程属于自己，防止越权查看其他课程/学生成绩
        if (isTeacher(request)) {
            if (courseId == null || !courseService.isCourseOfTeacher(getUsername(request), courseId)) {
                return Result.error("403", "无权限查询该课程的成绩");
            }
        }
        PageResult<Score> pageResult = scoreService.page(page, pageSize, studentId, courseId, term);
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
    public Result<String> add(@RequestBody Score score, HttpServletRequest request) {
        if (!checkCourseOwnership(score.getCourseId(), request)) {
            return Result.error("403", "无权限为该课程录入成绩");
        }
        scoreService.add(score);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id, HttpServletRequest request) {
        Score score = scoreService.selectById(id);
        if (score == null) {
            return Result.error("404", "成绩记录不存在");
        }
        if (!checkCourseOwnership(score.getCourseId(), request)) {
            return Result.error("403", "无权限删除该成绩");
        }
        scoreService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody Score score, HttpServletRequest request) {
        if (!checkCourseOwnership(score.getCourseId(), request)) {
            return Result.error("403", "无权限修改该成绩");
        }
        scoreService.update(score);
        return Result.success("更新成功");
    }

    /** 教师操作前校验课程归属；管理员直接放行（拦截器已保证无学生到达此处） */
    private boolean checkCourseOwnership(Integer courseId, HttpServletRequest request) {
        if (!isTeacher(request)) {
            return true;
        }
        return courseId != null && courseService.isCourseOfTeacher(getUsername(request), courseId);
    }

    private boolean isTeacher(HttpServletRequest request) {
        return "teacher".equals(jwtUtil.getRoleFromToken(request.getHeader("token")));
    }

    private String getUsername(HttpServletRequest request) {
        return jwtUtil.getUsernameFromToken(request.getHeader("token"));
    }
}
