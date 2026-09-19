package duyell.mapper;

import com.duyell.AiToolAudit;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 工具调用审计 Mapper。
 *
 * <p>写入路径刻意只有 insert：审计记录是不可变事实，不允许更新或删除。
 *
 * @author duyell
 */
@Mapper
public interface AiToolAuditMapper {

    /**
     * 写入一条审计记录。
     *
     * <p>依赖表上的 uk_ai_audit_request 唯一约束实现幂等：
     * 同一 requestId 重复写入会抛 DuplicateKeyException，由调用方决定是否忽略。
     */
    @Insert("""
            insert into ai_tool_audit
              (user_id, role, session_id, tool_name, risk_level, args_json, result_json,
               status, error_msg, confirm_id, request_id, duration_ms)
            values
              (#{userId}, #{role}, #{sessionId}, #{toolName}, #{riskLevel}, #{argsJson}, #{resultJson},
               #{status}, #{errorMsg}, #{confirmId}, #{requestId}, #{durationMs})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AiToolAudit audit);

    /** 按用户查最近的调用记录（诊断/审计查询用） */
    @Select("""
            select * from ai_tool_audit
            where user_id = #{userId}
            order by id desc
            limit #{limit}
            """)
    List<AiToolAudit> listByUser(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * 按幂等键查询已成功的执行记录。
     *
     * <p>幂等保护的依据：同一 request_id 最多只有一条成功记录
     * （由 uk_ai_audit_request 唯一约束保证），据此判断「这次操作是否已经执行过」。
     * 只取 SUCCESS —— 失败与拒绝不构成「已执行」，重试时应当允许真正再执行一次。
     */
    @Select("""
            select * from ai_tool_audit
            where request_id = #{requestId} and status = 'SUCCESS'
            limit 1
            """)
    AiToolAudit findSuccessfulByRequestId(@Param("requestId") String requestId);

    /** 按状态统计数量（阶段 4 可观测的基础聚合） */
    @Select("select count(*) from ai_tool_audit where status = #{status}")
    int countByStatus(@Param("status") String status);
}
