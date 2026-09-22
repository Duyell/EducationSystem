package duyell.ai.rag;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.Result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制度知识库的运维接口（RAG 开启时才存在）。
 *
 * <p>两个用途，都不是"为了好看"：
 * <ol>
 *   <li>{@code GET /ai/rag/search}：**排查与评测**。模型说"没找到依据"时，
 *       要能立刻分清是"检索没召回"还是"召回了但模型没用"；评测的命中率/MRR 也必须
 *       走这条与线上同源的检索链路（见 {@link PolicySearchService}）。</li>
 *   <li>{@code POST /ai/rag/reindex}：语料在逐章审计修订，改完要能重建索引，
 *       不该要求重启服务（只允许管理员，见 {@code LoginInterceptor} 的规则）。</li>
 * </ol>
 *
 * @author duyell
 */
@Slf4j
@RestController
@RequestMapping("/ai/rag")
@ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
public class PolicyRagController {

    private final PolicySearchService policySearchService;
    private final PolicyIndexer policyIndexer;

    public PolicyRagController(PolicySearchService policySearchService, PolicyIndexer policyIndexer) {
        this.policySearchService = policySearchService;
        this.policyIndexer = policyIndexer;
    }

    /** 检索制度条款（与 search_policy 工具同一条链路） */
    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam("query") String query,
                                              @RequestParam(value = "docId", required = false) String docId,
                                              @RequestParam(value = "topK", required = false) Integer topK) {
        List<PolicySearchService.PolicyHit> hits = policySearchService.search(query, docId, topK);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("count", hits.size());
        data.put("results", hits);
        return Result.success(data);
    }

    /** 重建索引（管理员）：语料修订后调用，不必重启服务 */
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex(HttpServletRequest request) {
        String operator = (String) request.getAttribute("username");
        log.info("管理员重建制度索引: operator={}", operator);
        int chunks = policyIndexer.reindex();
        return Result.success(Map.of("chunks", chunks, "operator", String.valueOf(operator)));
    }
}
