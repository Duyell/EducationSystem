package duyell.ai.audit;

import com.duyell.AiToolAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.AiToolAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工具调用审计服务。
 *
 * <p>核心不变量：<b>审计失败绝不能影响 Agent 主流程</b>。
 * 用户不该因为日志表写不进去而无法选课；但审计失败必须留下高优先级日志，
 * 否则「审计静默失效」会比「没有审计」更危险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAuditService {

    private static final int MAX_TEXT_LEN = 60000; // text 列上限约 64KB，留出余量
    private static final String TRUNCATED = "...[truncated]";

    /**
     * 与建表脚本一致的定长列宽度；越界必须显式处理，否则整条审计会被 MySQL 拒绝写入。
     *
     * <p><b>本常量与数据库列宽必须同时对齐</b>：只放宽其中一个没有意义 ——
     * 应用层更窄就会在这里被截断，列更窄就会被 MySQL 截断或拒绝写入。
     * 曾踩过：只把 `status` 列从 16 扩到 32、却漏改这里的 16，
     * 于是 `INVALID_ARGUMENTS`(17 字符) 仍被应用层截成 `INVALID_ARGUMENT`。
     * {@code AiAuditServiceTest#auditLimitsMatchDatabaseColumns} 现已同时校验两侧。
     *
     * <p>可见性为包级（非 private）以便测试直接引用这两个契约值，避免测试里重复硬编码。
     */
    static final int MAX_USER_ID_LEN = 32;
    static final int MAX_ROLE_LEN = 16;
    static final int MAX_TOOL_NAME_LEN = 64;
    static final int MAX_RISK_LEVEL_LEN = 16;
    static final int MAX_STATUS_LEN = 32;
    static final int MAX_CONFIRM_ID_LEN = 64;
    static final int MAX_REQUEST_ID_LEN = 64;

    private final AiToolAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    /**
     * 记录一次工具调用。
     *
     * @param userId     调用者
     * @param role       角色
     * @param toolName   工具名
     * @param riskLevel  风险等级
     * @param args       调用参数
     * @param result     执行结果（已脱敏）；被拒绝时可为拒绝原因
     * @param status     {@link AuditStatus} 之一
     * @param errorMsg   失败/拒绝详情，仅服务端可见
     * @param confirmId  危险操作确认令牌，可空
     * @param durationMs 耗时，可空
     */
    public void record(String userId, String role, String toolName, String riskLevel,
                       Map<String, Object> args, String result, String status,
                       String errorMsg, String confirmId, Long durationMs) {
        record(userId, role, toolName, riskLevel, args, result, status, errorMsg, confirmId,
                durationMs, null, null);
    }

    /**
     * 带幂等键的记录。
     *
     * @param requestId 幂等键；同一键只允许有一条成功记录（uk_ai_audit_request 唯一约束）。
     *                  非空时重复写入会命中唯一约束并抛 {@code DuplicateKeyException}，
     *                  此处降级为 debug —— 那正是幂等生效的预期路径。
     */
    public void record(String userId, String role, String toolName, String riskLevel,
                       Map<String, Object> args, String result, String status,
                       String errorMsg, String confirmId, Long durationMs, String requestId) {
        record(userId, role, toolName, riskLevel, args, result, status, errorMsg, confirmId,
                durationMs, requestId, null);
    }

    /**
     * 查询该幂等键是否已有成功执行记录，用于「不重复执行」判断。
     *
     * @return 已成功执行过的记录；无则返回 null。查询失败时返回 null（宁可重复执行一次，
     *         也不要因为审计查询故障把正常操作挡掉）。
     */
    public AiToolAudit findExecuted(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        try {
            return auditMapper.findSuccessfulByRequestId(requestId);
        } catch (Exception e) {
            log.error("幂等查询失败，本次按「未执行过」处理: requestId={}", requestId, e);
            return null;
        }
    }

    /**
     * 同 {@link #record}，但把最终写入的实体回传给调用方。
     *
     * <p>仅供测试使用：服务端字段清洗（截断、JSON 序列化）后的真实取值无法从外部观察，
     * 而「超长标识被截断而非整条丢弃」这类行为恰恰需要断言写入值本身。
     *
     * @param writtenOut 可为 null；非 null 时写入前会把清洗后的实体填入该对象
     */
    void record(String userId, String role, String toolName, String riskLevel,
                Map<String, Object> args, String result, String status,
                String errorMsg, String confirmId, Long durationMs,
                String requestId, AiToolAudit writtenOut) {
        try {
            AiToolAudit audit = new AiToolAudit();
            audit.setUserId(fit("user_id", userId, MAX_USER_ID_LEN));
            audit.setRole(fit("role", role, MAX_ROLE_LEN));
            audit.setToolName(fit("tool_name", toolName, MAX_TOOL_NAME_LEN));
            audit.setRiskLevel(fit("risk_level", riskLevel, MAX_RISK_LEVEL_LEN));
            audit.setArgsJson(toJson(args));
            audit.setResultJson(truncate(result));
            audit.setStatus(fit("status", status, MAX_STATUS_LEN));
            audit.setErrorMsg(truncate(errorMsg));
            audit.setConfirmId(fit("confirm_id", confirmId, MAX_CONFIRM_ID_LEN));
            audit.setRequestId(fit("request_id", requestId, MAX_REQUEST_ID_LEN));
            audit.setDurationMs(durationMs);

            if (writtenOut != null) {
                writtenOut.setUserId(audit.getUserId());
                writtenOut.setRole(audit.getRole());
                writtenOut.setToolName(audit.getToolName());
                writtenOut.setRiskLevel(audit.getRiskLevel());
                writtenOut.setArgsJson(audit.getArgsJson());
                writtenOut.setResultJson(audit.getResultJson());
                writtenOut.setStatus(audit.getStatus());
                writtenOut.setErrorMsg(audit.getErrorMsg());
                writtenOut.setConfirmId(audit.getConfirmId());
                writtenOut.setDurationMs(audit.getDurationMs());
            }

            auditMapper.insert(audit);
            if (writtenOut != null) {
                writtenOut.setId(audit.getId());
            }
        } catch (DuplicateKeyException e) {
            // 幂等命中：同一 requestId 已有一条成功记录，属预期行为，降级为 debug
            log.debug("审计记录已存在（幂等命中）: tool={}, user={}, requestId={}",
                    toolName, userId, requestId);
        } catch (Exception e) {
            // 审计不可用不能中断业务，但必须显式告警
            log.error("审计写入失败！tool={}, user={}, status={} —— 审计缺失，请检查 ai_tool_audit 表",
                    toolName, userId, status, e);
        }
    }

    private String toJson(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return truncate(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            log.warn("审计参数序列化失败，降级为 toString", e);
            return truncate(String.valueOf(args));
        }
    }

    /** 防止超长内容触发数据库列溢出（text 列装不下会让整条审计丢失） */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_TEXT_LEN ? s : s.substring(0, MAX_TEXT_LEN) + TRUNCATED;
    }

    /**
     * 把值收进列的宽度上限。
     *
     * <p>定长列溢出会让 MySQL 直接拒绝整条 INSERT，审计就此静默丢失。
     * 宁可截断并告警：内容略有残缺的审计记录，也远好过没有记录。
     * 正常情况下不该触发，一旦触发说明调用方传了非预期的标识。
     */
    private String fit(String column, String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        log.warn("审计字段超长已截断: column={}, maxLen={}, actualLen={}", column, maxLen, value.length());
        return value.substring(0, maxLen);
    }
}
