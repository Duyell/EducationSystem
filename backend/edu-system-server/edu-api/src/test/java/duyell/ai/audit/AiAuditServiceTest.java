package duyell.ai.audit;

import com.duyell.AiToolAudit;
import duyell.mapper.AiToolAuditMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计服务测试。
 *
 * <p>用真实 Spring 上下文 + 真实 MySQL（本地开发库 edujwxt），因为审计的价值
 * 恰恰在于「真的落库了」；桩掉 mapper 只能证明代码调用过它，不能证明 SQL 正确。
 *
 * <p>两个关键测试配置：
 * <ul>
 *   <li>{@code webEnvironment=NONE}：不需要 Web 容器，也避免与本地运行中的后端争用 8080。</li>
 *   <li>{@code @Transactional(NOT_SUPPORTED)}：<b>必须</b>。Spring Boot 的 @SpringBootTest
 *       默认给每个测试方法套一个回滚事务，导致审计写入在方法内即被回滚 ——
 *       表现为「插入成功但查不到、计数不增」（已实测踩到）。审计走真实提交路径才有意义。</li>
 * </ul>
 * 写入使用随机 user_id，避免只追加的真实表在多次运行间互相污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiAuditServiceTest {

    @Autowired
    private AiAuditService auditService;

    @Autowired
    private AiToolAuditMapper auditMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 每个测试用自己的 user_id，且必须能装进 varchar(32)。
     *
     * <p>审计表是只追加的真实表，既不回滚也不清理，因此共用同一个 user_id 会让
     * listByUser 读到历史运行累积的记录（已实测踩到：期望 1 条、实际 3 条）。
     *
     * <p>长度也必须受控：最初写成 "audit-test-" + 完整 UUID = 47 字符，超过列宽
     * 导致 INSERT 被 MySQL 拒绝，而「审计失败不外抛」的设计把它变成了
     * 「查不到数据」这一误导性症状。此处固定 30 字符。
     */
    private static String newTestUser() {
        return "audit-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    @Test
    void recordPersistsAuditRowReadableThroughMapper() {
        String user = newTestUser();
        int before = auditMapper.countByStatus(AuditStatus.SUCCESS);

        auditService.record(user, "student", "select_course", "DANGEROUS",
                Map.of("courseId", 5), "{\"message\":\"ok\"}", AuditStatus.SUCCESS,
                null, "confirm-abc-123", 42L);

        // 1) 计数器变化，证明真的写进了库
        assertEquals(before + 1, auditMapper.countByStatus(AuditStatus.SUCCESS),
                "成功审计记录应使计数 +1");

        // 2) 读回并逐字段校验映射正确（下划线列名 -> 驼峰属性）
        List<AiToolAudit> rows = auditMapper.listByUser(user, 10);
        assertEquals(1, rows.size(), "应能按用户查回刚写入的那一条记录");

        AiToolAudit row = rows.get(0);
        assertNotNull(row.getId(), "自增主键应回填");
        assertEquals("student", row.getRole());
        assertEquals("select_course", row.getToolName());
        assertEquals("DANGEROUS", row.getRiskLevel());
        assertEquals(AuditStatus.SUCCESS, row.getStatus());
        assertEquals("confirm-abc-123", row.getConfirmId());
        assertEquals(42L, row.getDurationMs());
        assertNotNull(row.getCreatedAt(), "created_at 应由数据库默认值填充");
        assertTrue(row.getArgsJson().contains("courseId"), "参数 JSON 应原样保存: " + row.getArgsJson());
        assertTrue(row.getResultJson().contains("ok"));
    }

    @Test
    void everyStatusValueIsPersistable() {
        for (String status : new String[]{AuditStatus.SUCCESS, AuditStatus.FAILED,
                AuditStatus.DENIED, AuditStatus.REJECTED_BY_USER}) {
            String user = newTestUser();
            auditService.record(user, "teacher", "enter_score", "DANGEROUS",
                    Map.of("courseId", 1), null, status, "detail-for-" + status, null, 1L);

            List<AiToolAudit> rows = auditMapper.listByUser(user, 10);
            assertEquals(1, rows.size(), status + " 状态应可写入");
            assertEquals(status, rows.get(0).getStatus(), "状态应原样读回");
        }
    }

    @Test
    void emptyArgsAreStoredAsEmptyJsonObject() {
        String user = newTestUser();
        auditService.record(user, "admin", "get_statistics", "READ_ONLY",
                Map.of(), "{}", AuditStatus.SUCCESS, null, null, 3L);

        AiToolAudit row = auditMapper.listByUser(user, 1).get(0);
        assertEquals("{}", row.getArgsJson(), "空参数应存为 {} 而非 null");
    }

    @Test
    void oversizedContentIsTruncatedInsteadOfLosingTheRecord() {
        String user = newTestUser();
        String huge = "x".repeat(70000);

        auditService.record(user, "student", "get_my_courses", "READ_ONLY",
                Map.of("blob", huge), huge, AuditStatus.SUCCESS, null, null, 1L);

        List<AiToolAudit> rows = auditMapper.listByUser(user, 1);
        assertEquals(1, rows.size(), "超长内容不应导致整条审计丢失");
        AiToolAudit row = rows.get(0);
        assertTrue(row.getResultJson().length() < 70000, "应被截断");
        assertTrue(row.getResultJson().endsWith("...[truncated]"), "应带截断标记");
    }

    /**
     * 定长列溢出不应导致整条审计丢失。
     *
     * <p>这是实测踩到过的坑：user_id 超过 varchar(32) 时 MySQL 拒绝 INSERT，
     * 而「审计失败不外抛」会把症状伪装成「查不到数据」。服务应截断并告警。
     *
     * <p>断言的是「服务端截断后的值」而非查回行数：该用例的输入是固定字符串，
     * 若按 user_id 查回计数，多次运行会累积（已实测：期望 1 条实际 2 条）。
     * 直接校验写库前的实体字段，既是被测行为本身，也与历史数据无关。
     */
    @Test
    void oversizedIdentifierIsTruncatedInsteadOfRejectingTheWholeRow() {
        String tooLong = "u".repeat(100);
        AiToolAudit written = new AiToolAudit();

        assertDoesNotThrow(() -> auditService.record(tooLong, "teacher", "enter_score", "DANGEROUS",
                Map.of(), "{}", AuditStatus.SUCCESS, null, null, 1L, null, written));

        assertEquals(32, written.getUserId().length(),
                "超长 user_id 应被截断到列宽，而不是让 MySQL 拒绝整条 INSERT");
        assertEquals(tooLong.substring(0, 32), written.getUserId());

        // 确认该条记录确实落库（用截断后的唯一前缀查回）
        List<AiToolAudit> rows = auditMapper.listByUser(written.getUserId(), 100);
        assertFalse(rows.isEmpty(), "截断后的记录应已落库");
    }

    /**
     * 回归测试：较长的状态值必须原样落库，不得被截断。
     *
     * <p>直接写一条 `INVALID_ARGUMENTS`(17 字符) 并经真实数据库读回比对。
     * 这是那次事故的核心断言 —— 当年若已有此测试，缺陷就不会发生：
     * 当时 status 列与应用层限制都是 16，写入后变成 `INVALID_ARGUMENT`，
     * 而测试只用短状态值（SUCCESS/FAILED），完全没覆盖。
     *
     * <p>注意本测试同时覆盖了「应用层截断」与「数据库截断」两处，
     * 因此只改其中一边（曾犯过的错）也会被它发现。
     */
    @Test
    void longStatusValuesArePersistedWithoutTruncation() {
        String user = newTestUser();

        auditService.record(user, "student", "evaluate_teacher", "DANGEROUS",
                Map.of("score", 9999), null, AuditStatus.INVALID_ARGUMENTS,
                "参数.score 不能大于 100.0，实际为 9999.0", null, null);

        AiToolAudit row = auditMapper.listByUser(user, 1).get(0);
        assertEquals(AuditStatus.INVALID_ARGUMENTS, row.getStatus(),
                "状态值被截断（应用层或列宽不足）: 实际为 " + row.getStatus());
        assertEquals(17, row.getStatus().length(), "长度应为 17，截断后会是 16");
        assertTrue(row.getErrorMsg().contains("不能大于"), "错误详情也应完整保留");
    }

    /**
     * 审计的「应用层长度限制」必须与「数据库列宽」两侧同时对齐，且所有常量要装得下。
     *
     * <p>这条测试的由来是一次真实事故：`status` 原为 varchar(16)，而
     * `INVALID_ARGUMENTS` / `DUPLICATE_SKIPPED` 都是 17 字符，被 {@code AiAuditService.fit()}
     * 截断成 `INVALID_ARGUMENT` / `DUPLICATE_SKIPPE`，导致按 status 聚合时记录分错组、
     * 「幂等命中」标记失真。当时 39 项单测全绿 —— 测试只用了短状态值，没人核对列宽。
     * 该缺陷最终在用本地 Ollama 做真实 LLM 验证时、由审计告警日志暴露。
     *
     * <p><b>第一次修复只做对了一半</b>：把数据库列扩到 32，却漏改应用层的
     * {@code MAX_STATUS_LEN = 16}，于是仍被应用层截断；而当时的测试只查列宽，
     * 因此照样通过。所以这里**必须同时断言两侧**，并且直接引用服务的常量而非另写一份数字。
     */
    @Test
    void auditLimitsMatchDatabaseColumns() {
        assertColumnWidthAtLeast("user_id", AiAuditService.MAX_USER_ID_LEN);
        assertColumnWidthAtLeast("role", AiAuditService.MAX_ROLE_LEN);
        assertColumnWidthAtLeast("tool_name", AiAuditService.MAX_TOOL_NAME_LEN);
        assertColumnWidthAtLeast("risk_level", AiAuditService.MAX_RISK_LEVEL_LEN);
        assertColumnWidthAtLeast("status", AiAuditService.MAX_STATUS_LEN);
        assertColumnWidthAtLeast("confirm_id", AiAuditService.MAX_CONFIRM_ID_LEN);
        assertColumnWidthAtLeast("request_id", AiAuditService.MAX_REQUEST_ID_LEN);

        // 常量本身也必须装得进应用层限制
        assertFitsLimit(AiAuditService.MAX_STATUS_LEN, "status",
                AuditStatus.SUCCESS, AuditStatus.FAILED, AuditStatus.DENIED,
                AuditStatus.REJECTED_BY_USER, AuditStatus.INVALID_ARGUMENTS, AuditStatus.DUPLICATE_SKIPPED);
        assertFitsLimit(AiAuditService.MAX_ROLE_LEN, "role", "student", "teacher", "admin");
        assertFitsLimit(AiAuditService.MAX_RISK_LEVEL_LEN, "risk_level",
                "READ_ONLY", "WRITE", "DANGEROUS", "UNKNOWN");
    }

    /** 数据库列宽必须 >= 应用层限制，否则列会先于应用层截断（或直接拒绝写入） */
    private void assertColumnWidthAtLeast(String column, int appLimit) {
        Integer dbWidth = columnWidth(column);
        assertNotNull(dbWidth, "未找到列定义: " + column);
        assertTrue(appLimit <= dbWidth,
                String.format("列 %s：应用层限制 %d > 数据库列宽 %d，写入会被数据库截断", column, appLimit, dbWidth));
        assertTrue(dbWidth > 0);
    }

    private void assertFitsLimit(int limit, String column, String... values) {
        for (String v : values) {
            assertTrue(v.length() <= limit,
                    String.format("常量 \"%s\"（%d 字符）超过列 %s 的应用层限制 %d，写入会被截断并导致统计失真",
                            v, v.length(), column, limit));
        }
    }

    private Integer columnWidth(String column) {
        return jdbcTemplate.queryForObject("""
                select CHARACTER_MAXIMUM_LENGTH from information_schema.COLUMNS
                where TABLE_SCHEMA = database() and TABLE_NAME = 'ai_tool_audit' and COLUMN_NAME = ?
                """, Integer.class, column);
    }

    /**
     * 最关键的不变量：审计失败不能影响 Agent 主流程（用户不该因为日志写不进去而无法选课）。
     *
     * <p>此处用手写桩而非 Mockito：spring-boot-starter-test 3.5.16 在离线仓库中解析到的
     * mockito/byte-buddy 组合在 JDK 21 上无法初始化 MockMaker。一个只会抛异常的
     * Mapper 实现足以覆盖该不变量，且不引入脆弱依赖。
     */
    @Test
    void auditFailureNeverPropagates() {
        AiToolAuditMapper failingMapper = new AiToolAuditMapper() {
            @Override
            public void insert(AiToolAudit audit) {
                throw new RuntimeException("simulated db outage");
            }

            @Override
            public List<AiToolAudit> listByUser(String userId, int limit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countByStatus(String status) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiToolAudit findSuccessfulByRequestId(String requestId) {
                throw new UnsupportedOperationException();
            }
        };
        AiAuditService service = new AiAuditService(failingMapper,
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertDoesNotThrow(() -> service.record(newTestUser(), "student", "select_course", "DANGEROUS",
                        Map.of("courseId", 1), "ok", AuditStatus.SUCCESS, null, null, 1L),
                "Mapper 抛异常时审计服务必须吞掉，不能冒泡中断业务流程");
    }

    /**
     * 幂等查询失败时按「未执行过」处理。
     *
     * <p>这是刻意选择的偏向：宁可重复执行一次（业务层还有唯一约束兜底），
     * 也不要因为审计查询故障把正常操作直接挡掉。
     */
    @Test
    void idempotencyLookupFailureDoesNotBlockTheOperation() {
        AiToolAuditMapper failingMapper = new AiToolAuditMapper() {
            @Override
            public void insert(AiToolAudit audit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AiToolAudit> listByUser(String userId, int limit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countByStatus(String status) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiToolAudit findSuccessfulByRequestId(String requestId) {
                throw new RuntimeException("simulated db outage");
            }
        };
        AiAuditService service = new AiAuditService(failingMapper,
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertDoesNotThrow(() -> service.findExecuted("some-request-id"),
                "幂等查询异常不得冒泡");
        assertNull(service.findExecuted("some-request-id"),
                "查询失败应返回 null（按未执行过处理），而不是抛异常");
        assertNull(service.findExecuted(null), "空幂等键应直接返回 null");
        assertNull(service.findExecuted("  "));
    }

    /**
     * 幂等键的写入与查回（真实数据库）。
     *
     * <p>这是阶段 0.6 的地基：靠 uk_ai_audit_request 唯一约束 + 查询成功记录，
     * 判断「这次操作是否已经执行过」，从而不重复执行。
     */
    @Test
    void requestIdIsPersistedAndLookupFindsTheSuccessfulExecution() {
        String user = newTestUser();
        String requestId = "req-" + java.util.UUID.randomUUID();

        auditService.record(user, "student", "select_course", "DANGEROUS",
                Map.of("courseId", 5), "{\"message\":\"选课成功\"}", AuditStatus.SUCCESS,
                null, requestId, 12L, requestId);

        AiToolAudit found = auditService.findExecuted(requestId);
        assertNotNull(found, "应能按幂等键查回已成功的执行记录");
        assertEquals(requestId, found.getRequestId());
        assertEquals("select_course", found.getToolName());
        assertTrue(found.getResultJson().contains("选课成功"),
                "查回的结果应可用于复用，避免重复执行: " + found.getResultJson());
    }

    /**
     * 只有成功记录才算「已执行」：失败记录不应让重试被幂等挡住。
     */
    @Test
    void failedExecutionIsNotTreatedAsAlreadyDone() {
        String user = newTestUser();
        String requestId = "req-failed-" + java.util.UUID.randomUUID();

        auditService.record(user, "student", "select_course", "DANGEROUS",
                Map.of("courseId", 5), "操作失败，请稍后重试", AuditStatus.FAILED,
                "simulated internal error", requestId, 12L, requestId);

        assertNull(auditService.findExecuted(requestId),
                "失败记录不构成「已执行」，重试应被允许");
    }
}
