package duyell.controller;

import duyell.service.AcademicWarningService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utils.JwtUtil;
import utils.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 学业预警接口（制度依据：{@code docs/policies/01-学籍管理规定.md} §5）。
 *
 * <p>权限：{@code /academic-warning/**} 仅 **学生与管理员**（见 {@code LoginInterceptor}）。
 * 学生只能看/确认**本人**的预警——学号一律取自 token，不接受入参，因此不存在越权读取他人预警的入口。
 * <b>教师没有该功能</b>。
 *
 * <p>两个接口的写法刻意如此：
 * <ul>
 *   <li>{@code GET /academic-warning/my} —— **只读**，不在读接口里写库（避免"查一次就产生副作用"）；</li>
 *   <li>{@code POST /academic-warning/my/read} —— 学生点"我知道了"时调用，把当前未通过学分裂
 *       记为已读水位线；之后只有变严重才再次提示。</li>
 * </ul>
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/academic-warning")
public class AcademicWarningController {

    private final AcademicWarningService academicWarningService;
    private final JwtUtil jwtUtil;

    /** 我的学业预警状态（含"是否需要弹通知"） */
    @GetMapping("/my")
    public Result<AcademicWarningService.WarningStatus> my(HttpServletRequest request) {
        return Result.success(academicWarningService.statusFor(currentUsername(request)));
    }

    /** 确认（标记已读）：把当前未通过学分记为水位线。幂等，重复调用不会重复写入。 */
    @PostMapping("/my/read")
    public Result<Map<String, Object>> markRead(HttpServletRequest request) {
        boolean recorded = academicWarningService.markRead(currentUsername(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recorded", recorded);
        data.put("message", recorded ? "已确认，将不再重复提示（情况变严重时会再次提示）"
                : "未记录新水位线（未达预警条件，或该水位线已确认过）");
        return Result.success(data);
    }

    private String currentUsername(HttpServletRequest request) {
        return jwtUtil.getUsernameFromToken(request.getHeader("token"));
    }
}
