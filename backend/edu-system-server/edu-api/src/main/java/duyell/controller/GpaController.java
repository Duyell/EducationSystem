package duyell.controller;

import duyell.service.CreditService;
import duyell.service.GpaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.JwtUtil;
import utils.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 绩点与学分接口。
 *
 * <p>权限（见 {@code docs/教务业务扩展设计.md} §4.4）：
 * <ul>
 *   <li>{@code /gpa/my}、{@code /gpa/rank}、{@code /audit/my} —— 仅学生，且**只能看本人**</li>
 *   <li>{@code /gpa/{studentId}}、{@code /gpa/rank/{studentId}}、{@code /audit/{studentId}} —— 管理员</li>
 * </ul>
 * <b>教师没有绩点相关功能</b>（用户明确要求），拦截器规则里不含 teacher。
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/gpa")
public class GpaController {

    private final GpaService gpaService;
    private final CreditService creditService;
    private final JwtUtil jwtUtil;

    /**
     * 我的绩点与专业内排名。
     *
     * <p>刻意合并为一个接口：前端"我的绩点"页面两者都要展示，分两次请求没有意义。
     */
    @GetMapping("/my")
    public Result<Map<String, Object>> myGpa(@RequestParam(required = false) String term,
                                             HttpServletRequest request) {
        String studentId = currentUsername(request);
        return Result.success(buildGpaPayload(studentId, term));
    }

    /** 我的专业内排名（单独入口，便于前端局部刷新） */
    @GetMapping("/rank")
    public Result<GpaService.RankResult> myRank(HttpServletRequest request) {
        return Result.success(gpaService.rankInMajor(currentUsername(request)));
    }

    /** 管理员查指定学生的绩点 */
    @GetMapping("/{studentId}")
    public Result<Map<String, Object>> gpaOf(@PathVariable String studentId,
                                            @RequestParam(required = false) String term) {
        return Result.success(buildGpaPayload(studentId, term));
    }

    /** 管理员查指定学生的排名 */
    @GetMapping("/rank/{studentId}")
    public Result<GpaService.RankResult> rankOf(@PathVariable String studentId) {
        return Result.success(gpaService.rankInMajor(studentId));
    }

    /** 我的毕业学分审核 */
    @GetMapping("/audit/my")
    public Result<CreditService.AuditResult> myAudit(HttpServletRequest request) {
        return Result.success(creditService.auditGraduation(currentUsername(request)));
    }

    /** 管理员查指定学生的毕业学分审核 */
    @GetMapping("/audit/{studentId}")
    public Result<CreditService.AuditResult> auditOf(@PathVariable String studentId) {
        return Result.success(creditService.auditGraduation(studentId));
    }

    private Map<String, Object> buildGpaPayload(String studentId, String term) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", studentId);
        payload.put("term", term == null ? "ALL" : term);
        payload.put("gpa", gpaService.calcGpa(studentId, term));
        payload.put("rank", gpaService.rankInMajor(studentId));
        return payload;
    }

    /** 从 token 取当前登录学号（拦截器已保证已登录） */
    private String currentUsername(HttpServletRequest request) {
        return jwtUtil.getUsernameFromToken(request.getHeader("token"));
    }
}
