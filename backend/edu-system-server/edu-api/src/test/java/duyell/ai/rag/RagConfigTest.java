package duyell.ai.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
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
 * pgvector 向量库的**真机**接线测试（默认不跑，需显式打开）。
 *
 * <p>为什么默认不跑：它依赖两个外部件——本机 Docker 里的 PostgreSQL+pgvector（5432）
 * 与 Ollama 的 {@code bge-m3} 嵌入模型。放进默认套件会让"测试全绿"变成碰运气的事
 * （本项目已多次栽在"外部依赖没起导致误判"上），因此与 {@code -Dai.live=true} 同一套路。
 *
 * <p>跑法：
 * <pre>
 * docker run -d --name edu-pgvector -e POSTGRES_PASSWORD=123456 -e POSTGRES_DB=edurag \
 *   -p 5432:5432 pgvector/pgvector:pg16
 * mvn -o -B test -pl edu-api -am -Dtest=RagConfigTest -Dai.rag.live=true
 * </pre>
 *
 * <p>它证明的是一条**完整链路**：Java → PostgreSQL 独立数据源 → pgvector 建表 →
 * Ollama bge-m3 生成 1024 维向量 → 写入 → 余弦相似度检索命中。
 * 这条链路里任何一环坏掉（维度不匹配、扩展没装、模型名写错、表名冲突），这里都会红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"ai.rag.enabled=true", "ai.rag.reindex-on-startup=false"})
@EnabledIfSystemProperty(named = "ai.rag.live", matches = "true")
class RagConfigTest {

    @Autowired
    @Qualifier("policyVectorStore")
    private VectorStore policyVectorStore;

    @Test
    void vectorStoreWritesAndRetrievesThroughPgvector() {
        assertNotNull(policyVectorStore, "ai.rag.enabled=true 时应装配 policyVectorStore");

        String id = "test-policy-chunk-1";
        // 语料取自制度文档的真实条款（成绩与绩点换算），避免用无意义文本测出"假通过"
        Document document = new Document(id,
                "补考通过的课程，成绩一律按 60 分记载，绩点按 60 分换算（即绩点 1.0）。",
                Map.of("docId", "05", "docTitle", "成绩构成与绩点换算办法",
                        "section", "§2.3 补考成绩记载"));

        policyVectorStore.add(List.of(document));
        try {
            List<Document> hits = policyVectorStore.similaritySearch(
                    SearchRequest.builder().query("补考通过以后绩点怎么算").topK(3).build());

            assertNotNull(hits);
            assertFalse(hits.isEmpty(), "刚写入的条款应当能被检索到");
            Document top = hits.get(0);
            assertEquals(id, top.getId(), "最相关的应该是刚写入的那条，实际=" + hits);
            // 元数据要能带回来：引用回填全靠它（文档名 + 章节）
            assertEquals("成绩构成与绩点换算办法", top.getMetadata().get("docTitle"));
            assertTrue(String.valueOf(top.getText()).contains("60 分"),
                    "命中文本应是原始条款：" + top.getText());
        } finally {
            // 可重复跑：测试自己写的数据自己删
            policyVectorStore.delete(List.of(id));
        }
    }
}
