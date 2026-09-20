package duyell.controller;

import com.duyell.CourseApply;
import duyell.service.CourseApplyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
 * 开课申请接口（教师提交/查自己的，管理员审批）。
 *
 * <p>流程（用户确认）：教师提交 → 管理员审批 → 审批通过才生成课程 → 下次开启选课时学生才能选。
 *
 * <p>⚠️ 权限已在 {@code LoginInterceptor} 登记，规则**顺序敏感**（见该类注释）。
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/course-apply")
public class CourseApplyController {

    private final CourseApplyService courseApplyService;
    private final JwtUtil jwtUtil;

    /**
     * 提交开课申请（教师）。
     *
     * <p>申请人**强制取自 token**，不采用请求体里的 teacherId —— 否则教师可以冒用他人身份提交。
     */
    @PostMapping
    public Result<CourseApply> submit(@RequestBody CourseApply apply, HttpServletRequest request) {
        apply.setTeacherId(currentUsername(request));
        return Result.success(courseApplyService.submit(apply));
    }

    /** 我的申请（教师） */
    @GetMapping("/my")
    public Result<List<CourseApply>> myApplies(HttpServletRequest request) {
        return Result.success(courseApplyService.listMine(currentUsername(request)));
    }

    /**
     * 申请列表（管理员）。
     *
     * <p>⚠️ {@code status} 为空时返回**全部状态**，不是"默认只看待审"——
     * 由前端显式传 {@code PENDING} 表示待办。想让后端默认过滤就在这里加默认值，
     * 不要在注释里假定。
     */
    @GetMapping
    public Result<List<CourseApply>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String term) {
        return Result.success(courseApplyService.listAll(status, term));
    }

    /** 申请详情：教师只能看自己的，管理员可看全部 */
    @GetMapping("/{id}")
    public Result<CourseApply> detail(@PathVariable Integer id, HttpServletRequest request) {
        CourseApply apply = courseApplyService.get(id);
        if (apply == null) {
            return Result.error("404", "开课申请不存在");
        }
        if (isTeacher(request) && !apply.getTeacherId().equals(currentUsername(request))) {
            return Result.error("403", "只能查看自己的申请");
        }
        return Result.success(apply);
    }

    /** 审批通过（管理员）：据申请生成 course 行 */
    @PostMapping("/{id}/approve")
    public Result<CourseApply> approve(@PathVariable Integer id, HttpServletRequest request) {
        return Result.success(courseApplyService.approve(id, currentUsername(request)));
    }

    /** 驳回（管理员）：reason 必填 */
    @PostMapping("/{id}/reject")
    public Result<String> reject(@PathVariable Integer id,
                                 @RequestBody Map<String, String> body,
                                 HttpServletRequest request) {
        courseApplyService.reject(id, currentUsername(request), body == null ? null : body.get("reason"));
        return Result.success("已驳回");
    }

    /** 从 token 取当前登录工号/学号（拦截器已保证已登录） */
    private String currentUsername(HttpServletRequest request) {
        return jwtUtil.getUsernameFromToken(request.getHeader("token"));
    }

    private boolean isTeacher(HttpServletRequest request) {
        return "teacher".equals(jwtUtil.getRoleFromToken(request.getHeader("token")));
    }
}
