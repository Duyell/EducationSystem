package duyell.ai.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolExecutionResult;
import duyell.ai.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制度问答的**真机端到端**测试（默认跳过，需 pgvector 容器 + Ollama bge-m3）。
 *
 * <p>跑法：
 * <pre>
 * docker run -d --name edu-pgvector -e POSTGRES_PASSWORD=123456 -e POSTGRES_DB=edurag -p 5432:5432 pgvector/pgvector:pg16
 * mvn -o -B test -pl edu-api -am "-Dtest=PolicySearchLiveTest" "-Dai.rag.live=true"
 * </pre>
 *
 * <p>它证明的是 ②+③ 的合并结果，而且是**按业务口径**证明的：
 * <ol>
 *   <li>真实语料能被切分并**幂等**写入（重建两次不会出现重复条款——这是"语料还在改"的前提）；</li>
 *   <li>通过**工具链路**（ToolRegistry → 声明式工具 → pgvector → bge-m3）能召回**正确的那份制度**
 *       （问补考绩点 → 命中 JW-05，而不是别的文档）；</li>
 *   <li>返回体里带 {@code citation}（引用回填），且三个角色都能用、风险等级是 READ_ONLY。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"ai.rag.enabled=true", "ai.rag.reindex-on-startup=false"})
@EnabledIfSystemProperty(named = "ai.rag.live", matches = "true")
class PolicySearchLiveTest {

    @Autowired
    private PolicyIndexer policyIndexer;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("policyVectorStore")
    private VectorStore policyVectorStore;

    /** 建索引很慢（每块都要调一次嵌入模型），同一个测试类里只需要建一次 */
    private static boolean indexed = false;

    private void ensureIndexed() {
        if (!indexed) {
            policyIndexer.reindex();
            indexed = true;
        }
    }

    @Test
    void corpusIsIndexedIdempotently() {
        int first = policyIndexer.reindex();
        assertTrue(first >= 60, "真实语料应切出足够多的分块，实际=" + first);

        // 再重建一次：分块数应完全相同（语料未变）
        int second = policyIndexer.reindex();
        assertEquals(first, second, "两次切分应得到相同分块数（语料未变）");
        indexed = true;

        List<org.springframework.ai.document.Document> hits = policyVectorStore.similaritySearch(
                SearchRequest.builder().query("补考通过后成绩按多少分记载").topK(20).build());
        assertFalse(hits.isEmpty(), "应能召回补考相关条款");
        // 幂等的判据是"同一分块 id 不重复"，而不是"只有一条提到 60 分"——
        // 语料里本来就有多处提到 60 分（及格判定、单点换算、重修办法、FAQ），
        // 把它们当成"重复入库"是**测试自己的错误假设**（第一版就是这么写错的）。
        List<String> ids = hits.stream().map(org.springframework.ai.document.Document::getId).toList();
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size(),
                "同一分块不得重复出现（id 是主键，出现重复说明写入方式有问题）：" + ids);
        assertTrue(hits.stream().anyMatch(d -> d.getText().contains("60 分")),
                "应至少召回一条含「60 分」的条款：" + hits);
    }

    @Test
    void toolRecallsTheRightPolicyWithCitation() throws Exception {
        ensureIndexed();

        for (String role : List.of("student", "teacher", "admin")) {
            ToolDefinition tool = toolRegistry.getTool(role, "search_policy");
            assertNotNull(tool, "角色 " + role + " 应能用 search_policy");
            assertEquals(RiskLevel.READ_ONLY, tool.riskLevel(), "查制度是只读操作，不该要确认卡片");

            ToolExecutionResult result = toolRegistry.executeForRole(tool, role,
                    Map.of("query", "补考通过以后绩点怎么算", "topK", 5), "2023001");
            assertTrue(result.isSuccess(), role + " 执行应成功：" + result.status() + " / " + result.errorDetail());

            Map<String, Object> payload = objectMapper.readValue(result.payload(),
                    new TypeReference<>() {
                    });
            assertEquals(true, payload.get("found"), "应当检索到制度依据：" + result.payload());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");
            assertFalse(results.isEmpty());

            // 不断言"top1 必须是 JW-05"：FAQ（docId=10）是按问句写的，对"补考绩点怎么算"这种问法
            // 本就是更贴近的答案——第一版把 top1 钉死在 05 上，是**过度指定实现细节**。
            // 业务口径的断言是：权威条款（JW-05）必须能被召回，且它给出了真正的规则文本。
            Map<String, Object> authoritative = results.stream()
                    .filter(r -> "05".equals(r.get("docId")))
                    .findFirst().orElse(null);
            assertNotNull(authoritative, "应召回《成绩构成与绩点换算办法》(JW-05)，实际=" + results);
            assertTrue(String.valueOf(authoritative.get("text")).contains("60 分"),
                    "JW-05 的条款原文要给出：" + authoritative.get("text"));

            // 引用回填：每一条结果都要能说清"出自哪份制度哪一节"
            for (Map<String, Object> r : results) {
                assertTrue(String.valueOf(r.get("citation")).contains(String.valueOf(r.get("docTitle"))),
                        "citation 必须含文档名：" + r.get("citation"));
                assertNotNull(r.get("section"), "citation 必须带章节：" + r);
            }
        }
    }

    @Test
    void emptyResultIsReportedAsNotFoundInsteadOfFabricating() throws Exception {
        ensureIndexed();

        ToolDefinition tool = toolRegistry.getTool("student", "search_policy");
        // 用不存在的文档编号限定检索 → 必然为空，验证"查不到"走的是结构化空结果
        ToolExecutionResult result = toolRegistry.executeForRole(tool, "student",
                Map.of("query", "学分要求", "docId", "99"), "2023001");

        assertTrue(result.isSuccess());
        Map<String, Object> payload = objectMapper.readValue(result.payload(),
                new TypeReference<>() {
                });
        assertEquals(false, payload.get("found"), "空结果应如实返回 found=false：" + result.payload());
        assertNotNull(payload.get("message"), "空结果必须带可读说明，模型才知道要如实回答没找到");
    }
}
