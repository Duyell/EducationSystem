package duyell.controller;

import com.duyell.PlanCourse;
import com.duyell.TrainingPlan;
import duyell.service.CreditService;
import duyell.service.TrainingPlanService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 培养计划接口。
 *
 * <p>权限（见 {@code docs/教务业务扩展设计.md} §4.4）：
 * <ul>
 *   <li>{@code /training-plan/my} —— 仅学生，返回**本人适用**的方案（专业+年级推导）</li>
 *   <li>其余写接口 —— 仅管理员（拦截器规则保证）</li>
 * </ul>
 *
 * <p>注意：学生查方案走 `/my` 而不是 `/training-plan/{id}`，
 * 避免学生遍历 id 查看其它专业的方案（虽非敏感数据，但无此需求）。
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/training-plan")
public class TrainingPlanController {

    private final TrainingPlanService trainingPlanService;
    private final CreditService creditService;
    private final JwtUtil jwtUtil;

    /**
     * 我的培养计划（含全部课程明细与毕业审核结果）。
     *
     * <p>一次返回三者是刻意的：前端"我的方案"页面要同时展示方案、明细、达标情况；
     * 且审核结果依赖同一份方案，分开请求会出现口径不一致的可能。
     */
    @GetMapping("/my")
    public Result<Map<String, Object>> myPlan(HttpServletRequest request) {
        String studentId = jwtUtil.getUsernameFromToken(request.getHeader("token"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", studentId);
        payload.put("placement", trainingPlanService.getStudentPlacement(studentId));

        TrainingPlan plan = trainingPlanService.resolvePlanForStudent(studentId);
        payload.put("plan", plan);
        payload.put("courses", plan == null ? List.of() : trainingPlanService.listPlanCourses(plan.getId()));
        payload.put("audit", creditService.auditGraduation(studentId));
        return Result.success(payload);
    }

    /** 方案列表（管理员） */
    @GetMapping
    public Result<List<TrainingPlan>> list(@RequestParam(required = false) Integer majorId,
                                           @RequestParam(required = false) String grade) {
        return Result.success(trainingPlanService.listPlans(majorId, grade));
    }

    /** 方案详情 + 课程明细（管理员） */
    @GetMapping("/{planId}")
    public Result<Map<String, Object>> detail(@PathVariable Integer planId) {
        TrainingPlan plan = trainingPlanService.getPlan(planId);
        if (plan == null) {
            return Result.error("404", "培养计划不存在");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plan", plan);
        payload.put("courses", trainingPlanService.listPlanCourses(planId));
        return Result.success(payload);
    }

    /** 新建方案（管理员），返回新方案 id */
    @PostMapping
    public Result<Integer> create(@RequestBody TrainingPlan plan) {
        return Result.success(trainingPlanService.createPlan(plan));
    }

    /** 更新方案（管理员） */
    @PutMapping
    public Result<String> update(@RequestBody TrainingPlan plan) {
        trainingPlanService.updatePlan(plan);
        return Result.success("更新成功");
    }

    /** 向方案添加课程明细（管理员） */
    @PostMapping("/course")
    public Result<String> addCourse(@RequestBody PlanCourse planCourse) {
        trainingPlanService.addPlanCourse(planCourse);
        return Result.success("添加成功");
    }

    /** 从方案移除课程明细（管理员） */
    @DeleteMapping("/course/{planCourseId}")
    public Result<String> removeCourse(@PathVariable Integer planCourseId) {
        trainingPlanService.removePlanCourse(planCourseId);
        return Result.success("删除成功");
    }
}
