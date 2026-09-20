package duyell.controller;

import com.duyell.SelectionRound;
import com.duyell.SelectionRoundScope;
import duyell.service.SelectionRoundService;
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

import java.util.List;
import java.util.Map;

/**
 * 选课轮次接口。
 *
 * <p>权限（docs/教务业务扩展设计.md §4.4）：
 * <ul>
 *   <li>{@code /selection-round/**} 写操作 —— 仅管理员（"只有管理员开启选课后学生才能选"）</li>
 *   <li>{@code /selection-round/current} (GET) —— 学生与管理员：查"我现在能不能选/退"</li>
 * </ul>
 *
 * <p>⚠️ 权限已在 {@code LoginInterceptor} 登记，规则**顺序敏感**：
 * {@code /current} 必须排在 {@code /selection-round/**}（仅 admin）之前。
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/selection-round")
public class SelectionRoundController {

    private final SelectionRoundService selectionRoundService;
    private final JwtUtil jwtUtil;

    /**
     * 当前选课状态（学生看自己的；管理员带 studentId 可代查）。
     *
     * <p>学生页面靠它显示「未开放／可选课／可退课」以及原因。
     */
    @GetMapping("/current")
    public Result<SelectionRoundService.SelectionStatus> current(@RequestParam String term,
                                                                @RequestParam(required = false) String studentId,
                                                                HttpServletRequest request) {
        String target = isAdmin(request) && studentId != null && !studentId.isBlank()
                ? studentId
                : currentUsername(request);
        return Result.success(selectionRoundService.statusFor(target, term));
    }

    /** 轮次列表（管理员；term/status 可空） */
    @GetMapping
    public Result<List<SelectionRound>> list(@RequestParam(required = false) String term,
                                            @RequestParam(required = false) Integer status) {
        return Result.success(selectionRoundService.list(term, status));
    }

    /** 轮次详情（含适用范围） */
    @GetMapping("/{id}")
    public Result<SelectionRound> detail(@PathVariable Integer id) {
        SelectionRound round = selectionRoundService.get(id);
        return round == null ? Result.error("404", "选课轮次不存在") : Result.success(round);
    }

    /** 新建轮次（默认关闭，需显式开启） */
    @PostMapping
    public Result<SelectionRound> create(@RequestBody SelectionRound round) {
        return Result.success(selectionRoundService.create(round));
    }

    /** 修改轮次 */
    @PutMapping
    public Result<String> update(@RequestBody SelectionRound round) {
        selectionRoundService.update(round);
        return Result.success("更新成功");
    }

    /** 开启 / 关闭选课（status: 1=开启 0=关闭） */
    @PostMapping("/{id}/status")
    public Result<String> setStatus(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("status");
        if (raw == null) {
            return Result.error("400", "缺少 status");
        }
        Integer status;
        try {
            status = raw instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return Result.error("400", "status 必须是 1（开启）或 0（关闭）");
        }
        selectionRoundService.setStatus(id, status);
        return Result.success(status == SelectionRound.STATUS_OPEN ? "选课已开启" : "选课已关闭");
    }

    /** 删除轮次（连带删除适用范围） */
    @DeleteMapping("/{id}")
    public Result<String> remove(@PathVariable Integer id) {
        selectionRoundService.remove(id);
        return Result.success("删除成功");
    }

    // ---------- 适用范围 ----------

    @GetMapping("/{roundId}/scope")
    public Result<List<SelectionRoundScope>> scopes(@PathVariable Integer roundId) {
        return Result.success(selectionRoundService.listScopes(roundId));
    }

    @PostMapping("/scope")
    public Result<SelectionRoundScope> addScope(@RequestBody SelectionRoundScope scope) {
        return Result.success(selectionRoundService.addScope(scope));
    }

    @DeleteMapping("/scope/{scopeId}")
    public Result<String> removeScope(@PathVariable Integer scopeId) {
        selectionRoundService.removeScope(scopeId);
        return Result.success("删除成功");
    }

    private String currentUsername(HttpServletRequest request) {
        return jwtUtil.getUsernameFromToken(request.getHeader("token"));
    }

    private boolean isAdmin(HttpServletRequest request) {
        return "admin".equals(jwtUtil.getRoleFromToken(request.getHeader("token")));
    }
}
