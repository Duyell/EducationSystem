package duyell.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 制度检索（**唯一的检索实现**）。
 *
 * <p>为什么单独抽出来：检索有两条调用方——模型用的 `search_policy` 工具，与排查/评测用的
 * HTTP 接口。如果各写一遍"构造 SearchRequest + 相似度下限 + docId 过滤 + 引用拼装"，
 * 评测测到的就不是线上那条链路了（这类"评测与线上两套实现"是本项目一直避免的）。
 *
 * <p>评测尤其需要它：12 条问题若都经模型，既慢（每条一分钟）又把模型噪声混进**检索**指标，
 * 命中率/MRR 就不再是检索本身的质量。
 *
 * @author duyell
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
public class PolicySearchService {

    /** 一条命中的制度条款（citation 直接给模型/界面用：出自哪份制度哪一节） */
    public record PolicyHit(String docId, String docTitle, String section, String text, String citation) {
    }

    private final VectorStore policyVectorStore;
    private final int defaultTopK;
    private final double similarityThreshold;

    public PolicySearchService(@Qualifier("policyVectorStore") VectorStore policyVectorStore,
                               @Value("${ai.rag.top-k:5}") int defaultTopK,
                               @Value("${ai.rag.similarity-threshold:0.35}") double similarityThreshold) {
        this.policyVectorStore = policyVectorStore;
        this.defaultTopK = defaultTopK;
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 检索制度条款。
     *
     * @param docId 可选：限定某一份文档（{@code 01}~{@code 10}）
     */
    public List<PolicyHit> search(String query, String docId, Integer topK) {
        int limit = topK == null || topK <= 0 ? defaultTopK : Math.min(topK, 50);
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(limit);
        // 相似度下限：过低会把无关条款一起喂给模型（"检索到了"反而更容易答偏）
        if (similarityThreshold > 0) {
            builder.similarityThreshold(similarityThreshold);
        }
        if (docId != null && !docId.isBlank()) {
            builder.filterExpression("docId == '" + docId.trim() + "'");
        }

        List<Document> hits = policyVectorStore.similaritySearch(builder.build());
        List<PolicyHit> results = new ArrayList<>();
        if (hits == null) {
            return results;
        }
        for (Document hit : hits) {
            String title = String.valueOf(hit.getMetadata().get("docTitle"));
            String section = String.valueOf(hit.getMetadata().get("section"));
            results.add(new PolicyHit(
                    String.valueOf(hit.getMetadata().get("docId")),
                    title, section, hit.getText(), title + " " + section));
        }
        log.info("制度检索: query={}, docId={}, topK={}, 命中={}", query, docId, limit, results.size());
        return results;
    }
}
