package duyell.controller;

import com.duyell.ClassTime;
import com.duyell.ClassTimeApply;
import duyell.service.ScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.JwtUtil;
import utils.Result;

import java.util.List;
import java.util.Map;

/**
 * 排课与课表接口。
 *
 * <p>分工：
 * <ul>
 *   <li>课表查询（我的课表 / 某课时间 / 全量）</li>
 *   <li>冲突检测 {@code POST /class-time/check} —— 排课前先问"会不会撞"</li>
 *   <li>排课申请（教师提交、管理员审批）{@code /class-time/apply/**}</li>
 * </ul>
 *
 * <p>⚠️ 管理员**没有**"直接插一条课表"的接口是刻意的：所有排课都必须留痕在
 * {@code class_time_apply} 里（谁申请、谁审批、有没有冲突），否则课表就不可追溯了。
 *
 * <p>⚠️ 权限已在 {@code LoginInterceptor} 登记，规则**顺序敏感**（见该类注释）。
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/class-time")
public class ClassTimeController {

    private final ScheduleService scheduleService;
    private final JwtUtil jwtUtil;

    // ==================== 课表查询 ====================

    /** 我的课表（教师）。管理员可传 teacherId 查指定教师 */
    @GetMapping("/my")
    public Result<List<ClassTime>> myTimetable(@RequestParam(required = false) String term,
                                               @RequestParam(required = false) String teacherId,
                                               HttpServletRequest request) {
        String target = isTeacher(request) ? currentUsername(request) : teacherId;
        if (target == null || target.isBlank()) {
            // 管理员不传 teacherId 时返回该学期全量，便于总览
            return Result.success(scheduleService.listAll(term, null));
        }
        return Result.success(scheduleService.listByTeacher(target, term));
    }

    /** 某门课的上课时间 */
    @GetMapping("/course/{courseId}")
    public Result<List<ClassTime>> byCourse(@PathVariable Integer courseId) {
        return Result.success(scheduleService.listByCourse(courseId));
    }

    /** 全量课表（管理员；可按学期/星期筛） */
    @GetMapping
    public Result<List<ClassTime>> all(@RequestParam(required = false) String term,
                                       @RequestParam(required = false) Integer weekday) {
        return Result.success(scheduleService.listAll(term, weekday));
    }

    /** 删除一条排课（管理员） */
    @DeleteMapping("/{id}")
    public Result<String> remove(@PathVariable Integer id) {
        scheduleService.removeClassTime(id);
        return Result.success("删除成功");
    }

    // ==================== 冲突检测 ====================

    /**
     * 冲突检测：给定候选时段，返回会与谁相撞。
     *
     * <p>给了 {@code courseId} 就用该课程的学期与授课教师；否则必须给出 {@code term}，
     * 并按当前登录教师（或管理员显式指定的 teacherId）检查。
     */
    @PostMapping("/check")
    public Result<ScheduleService.ConflictResult> check(@RequestBody ClassTime candidate,
                                                        @RequestParam(required = false) String term,
                                                        @RequestParam(required = false) Integer roomId,
                                                        @RequestParam(required = false) String teacherId,
                                                        HttpServletRequest request) {
        Integer room = roomId != null ? roomId : candidate.getRoomId();
        if (candidate.getCourseId() != null) {
            return Result.success(scheduleService.checkConflictForCourse(candidate.getCourseId(), room,
                    candidate.getWeekday(), candidate.getStartPeriod(), candidate.getEndPeriod(),
                    candidate.getStartWeek(), candidate.getEndWeek()));
        }
        if (term == null || term.isBlank()) {
            return Result.error("400", "未指定课程时必须给出学期 term");
        }
        // 教师只能查自己：teacherId 强制取自 token，不采用请求参数
        String owner = isTeacher(request) ? currentUsername(request) : teacherId;
        return Result.success(scheduleService.checkConflict(null, term, owner, room,
                candidate.getWeekday(), candidate.getStartPeriod(), candidate.getEndPeriod(),
                candidate.getStartWeek(), candidate.getEndWeek()));
    }

    // ==================== 排课申请 ====================

    /** 提交排课申请（教师）。返回体里带 conflictInfo，供前端提示"会和谁撞" */
    @PostMapping("/apply")
    public Result<ClassTimeApply> submitApply(@RequestBody ClassTimeApply apply, HttpServletRequest request) {
        apply.setTeacherId(currentUsername(request));
        return Result.success(scheduleService.submitClassTimeApply(apply, isTeacher(request)));
    }

    /** 我的排课申请（教师） */
    @GetMapping("/apply/my")
    public Result<List<ClassTimeApply>> myApplies(@RequestParam(required = false) String status,
                                                  HttpServletRequest request) {
        return Result.success(scheduleService.listClassTimeApplies(currentUsername(request), status, null));
    }

    /** 排课申请列表（管理员） */
    @GetMapping("/apply")
    public Result<List<ClassTimeApply>> applies(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) Integer courseId) {
        return Result.success(scheduleService.listClassTimeApplies(null, status, courseId));
    }

    /** 审批排课申请（管理员）：有冲突则不落课表并返回冲突详情 */
    @PostMapping("/apply/{id}/approve")
    public Result<ClassTimeApply> approveApply(@PathVariable Integer id,
                                               @RequestParam(required = false) Integer roomId,
                                               HttpServletRequest request) {
        return Result.success(scheduleService.approveClassTimeApply(id, currentUsername(request), roomId));
    }

    /** 驳回排课申请（管理员） */
    @PostMapping("/apply/{id}/reject")
    public Result<String> rejectApply(@PathVariable Integer id,
                                      @RequestBody Map<String, String> body,
                                      HttpServletRequest request) {
        scheduleService.rejectClassTimeApply(id, currentUsername(request),
                body == null ? null : body.get("reason"));
        return Result.success("已驳回");
    }

    /** 从 token 取当前登录账号（拦截器已保证已登录） */
    private String currentUsername(HttpServletRequest request) {
        return jwtUtil.getUsernameFromToken(request.getHeader("token"));
    }

    private boolean isTeacher(HttpServletRequest request) {
        return "teacher".equals(jwtUtil.getRoleFromToken(request.getHeader("token")));
    }
}
