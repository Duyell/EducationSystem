package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 工具调用审计日志（对应表 ai_tool_audit）。
 *
 * <p>每一次工具调用决策都会落一条：成功、执行失败、越权拒绝、用户拒绝。
 * 这是「Agent 做过什么」的唯一权威记录，也是后续幂等与可观测的数据源。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiToolAudit {

    private Long id;

    /** 调用者标识（学号/工号/用户名） */
    private String userId;

    /** 调用者角色：student/teacher/admin */
    private String role;

    /** 会话标识（阶段 1 引入多轮记忆后启用） */
    private String sessionId;

    /** 工具名 */
    private String toolName;

    /** 风险等级：READ_ONLY/WRITE/DANGEROUS */
    private String riskLevel;

    /** 调用参数（JSON） */
    private String argsJson;

    /** 执行结果（JSON，已脱敏；越权时记录拒绝原因） */
    private String resultJson;

    /** 状态：SUCCESS/FAILED/DENIED/REJECTED_BY_USER */
    private String status;

    /** 失败或拒绝的详情（仅服务端与审计可见，不对外暴露） */
    private String errorMsg;

    /** 危险操作确认令牌（经人工确认的操作可关联） */
    private String confirmId;

    /** 幂等键；表上有唯一约束，保证同一请求不重复落库 */
    private String requestId;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    private LocalDateTime createdAt;
}
