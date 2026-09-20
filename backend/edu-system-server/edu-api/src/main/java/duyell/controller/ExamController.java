package duyell.controller;

import com.duyell.ExamSchedule;
import duyell.service.ExamService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.JwtUtil;
import utils.Result;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 考试安排接口。
 *
 * <p>权限（docs/教务业务扩展设计.md §4.4）：
 * <ul>
 *   <li>{@code /exam/**} 写操作 —— 仅管理员</li>
 *   <li>{@code /exam/my} (GET) —— 学生查自己的考试（**必须排在前缀规则之前**）</li>
 * </ul>
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/exam")
public class ExamController {

    private final ExamService examService;
    private final JwtUtil jwtUtil;

    /**
     * 我的考试（学生）＝ 我已选课程的考试。
     *
     * @param upcoming 只看还没开考的（默认 false，便于学生回看已考完的）
     */
    @GetMapping("/my")
    public Result<List<ExamSchedule>> myExams(@RequestParam(required = false) String term,
                                              @RequestParam(required = false, defaultValue = "false") Boolean upcoming,
                                              HttpServletRequest request) {
        String studentId = jwtUtil.getUsernameFromToken(request.getHeader("token"));
        return Result.success(examService.listByStudent(studentId, term, Boolean.TRUE.equals(upcoming)));
    }

    /** 考试列表（管理员；term / courseId / examType / status 均可空） */
    @GetMapping
    public Result<List<ExamSchedule>> list(@RequestParam(required = false) String term,
                                          @RequestParam(required = false) Integer courseId,
                                          @RequestParam(required = false) String examType,
                                          @RequestParam(required = false) Integer status) {
        return Result.success(examService.list(term, courseId, examType, status));
    }

    /** 某门课的考试安排 */
    @GetMapping("/course/{courseId}")
    public Result<List<ExamSchedule>> byCourse(@PathVariable Integer courseId) {
        return Result.success(examService.listByCourse(courseId));
    }

    @GetMapping("/{id}")
    public Result<ExamSchedule> detail(@PathVariable Integer id) {
        ExamSchedule exam = examService.get(id);
        return exam == null ? Result.error("404", "考试安排不存在") : Result.success(exam);
    }

    /**
     * 冲突检测（管理员排考前先试算）。
     *
     * <p>body: {@code {courseId, examTime, durationMinutes, roomId?, excludeExamId?}}
     */
    @PostMapping("/check")
    public Result<ExamService.ConflictResult> check(@RequestBody Map<String, Object> body) {
        Integer courseId = toInt(body.get("courseId"));
        Integer duration = toInt(body.get("durationMinutes"));
        Integer roomId = toInt(body.get("roomId"));
        Integer excludeExamId = toInt(body.get("excludeExamId"));
        LocalDateTime examTime = toDateTime(body.get("examTime"));
        if (examTime == null) {
            return Result.error("400", "examTime 必须是 yyyy-MM-ddTHH:mm:ss 格式");
        }
        return Result.success(examService.checkConflict(courseId, examTime, duration, roomId, excludeExamId));
    }

    /** 新增考试（管理员）：有冲突会被拒绝 */
    @PostMapping
    public Result<ExamSchedule> create(@RequestBody ExamSchedule exam) {
        return Result.success(examService.create(exam));
    }

    /** 修改考试（管理员） */
    @PutMapping
    public Result<String> update(@RequestBody ExamSchedule exam) {
        examService.update(exam);
        return Result.success("更新成功");
    }

    /** 删除考试（管理员） */
    @DeleteMapping("/{id}")
    public Result<String> remove(@PathVariable Integer id) {
        examService.remove(id);
        return Result.success("删除成功");
    }

    private static Integer toInt(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime toDateTime(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
