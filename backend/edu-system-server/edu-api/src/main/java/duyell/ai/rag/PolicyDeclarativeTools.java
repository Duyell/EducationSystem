package duyell.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.ai.tool.declarative.DeclarativeToolContext;
import duyell.ai.tool.declarative.DeclarativeToolGroup;
import duyell.ai.tool.declarative.ToolMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制度问答工具（M3 计划 2.3 的落地形态）。
 *
 * <p><b>为什么做成"工具"而不是 RAG Advisor</b>：本项目是自写循环 + {@code AgentRuntime}，
 * 模型已经会调用工具。做成工具之后，检索天然获得四道闸门（角色白名单、参数校验、审计、
 * 事件可见），也能被现有评测与审计链路直接覆盖——**不引入第二套检索链路**。
 * 代价是"是否检索"由模型决定（Advisor 是每轮强制注入），这对制度问答恰好合理：
 * 只有政策类问题才需要检索，查成绩时不必浪费上下文。
 *
 * <p><b>引用回填</b>：返回给模型的每条结果都带 {@code docTitle} 与 {@code section}，
 * 提示词要求它回答时注明来源。这也是这个工具存在的意义——把"模型常识"换成"有据可依"。
 *
 * <p>三个角色都能用（学生问学分要求、教师问成绩录入规范、管理员问排课规则），
 * 因此覆写 {@link #roles()} 注册到三个角色下（见 {@code DeclarativeToolGroup#roles()}）。
 *
 * @author duyell
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
public class PolicyDeclarativeTools implements DeclarativeToolGroup {

    private static final String[] ALL_ROLES = {"student", "teacher", "admin"};

    private final PolicySearchService policySearchService;
    private final ObjectMapper objectMapper;

    public PolicyDeclarativeTools(PolicySearchService policySearchService, ObjectMapper objectMapper) {
        this.policySearchService = policySearchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String role() {
        return "student";
    }

    @Override
    public List<String> roles() {
        return List.of(ALL_ROLES);
    }

    /**
     * 检索教务制度知识库。
     *
     * <p>描述里刻意写了两件"因此你该怎么办"：一是**先检索再回答**，二是**检索不到就直说**。
     * 上一轮迁移的教训：工具描述是给模型的指令，讲机制时必须连带说清动作，否则模型会自行发明行为。
     */
    @Tool(name = "search_policy",
            description = "检索学校教务制度知识库（学籍、选课退课、重修补考、毕业学分、成绩与绩点、排课教室、"
                    + "考试、教学评价、成绩录入规范等），返回命中的条款原文与出处。"
                    + "当学生/教师/管理员问到「学校是怎么规定的」「能不能」「按什么算」这类**制度依据**问题时，"
                    + "先用本工具检索，再依据检索到的条款回答，并在回答里注明来源（文档名 + 章节）。"
                    + "如果检索结果为空，就明确回答「制度库里没有找到相关条款」，不要凭常识编造规定。"
                    + "查询具体数据（我的成绩、我的课表、选课名单等）请用其它工具，本工具只查制度条文。")
    @ToolMeta(displayName = "查询教务制度", riskLevel = RiskLevel.READ_ONLY)
    public String searchPolicy(
            @ToolParam(description = "要查的制度问题或关键词，用自然语言描述即可（例如「补考通过后绩点怎么算」）",
                    required = true)
            String query,
            @ToolParam(description = "可选：限定文档编号（01~10），只在某一份制度里查；省略则全库检索",
                    required = false)
            String docId,
            @ToolParam(description = "可选：返回条数，默认 5，最多 10；省略则用默认值",
                    required = false)
            Integer topK,
            ToolContext context) throws Exception {
        // 角色只用于审计与排查（角色白名单已由 ToolRegistry 把关）
        String role = DeclarativeToolContext.currentRole(context);

        // 检索实现只有一处（PolicySearchService）：工具与排查/评测接口共用同一条链路，
        // 否则评测测到的就不是线上那条链路了
        List<PolicySearchService.PolicyHit> hits = policySearchService.search(query, docId, topK);
        log.info("制度检索: role={}, query={}, docId={}, 命中={}", role, query, docId, hits.size());

        if (hits.isEmpty()) {
            // 明确的结构化空结果：让模型能区分"没查到"与"工具失败"
            return objectMapper.writeValueAsString(Map.of(
                    "found", false,
                    "message", "制度库里没有找到与「" + query + "」相关的条款，请如实告知用户没有找到依据"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (PolicySearchService.PolicyHit hit : hits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", hit.docId());
            item.put("docTitle", hit.docTitle());
            item.put("section", hit.section());
            item.put("text", hit.text());
            // 引用回填：提示词要求模型在回答里带上这两项（文档名 + 章节）
            item.put("citation", hit.citation());
            results.add(item);
        }
        return objectMapper.writeValueAsString(Map.of("found", true, "count", results.size(), "results", results));
    }
}

