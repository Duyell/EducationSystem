package duyell.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制度语料切分测试（纯逻辑，不需要数据库 / 向量库 / 嵌入模型）。
 *
 * <p>切分是 RAG 里最"不出声"的一步：切错了不会报错，只会表现成"检索到了但答不对"。
 * 因此这里盯四件事：
 * <ol>
 *   <li><b>按章节切</b>：每个分块的 {@code section} 必须是文档里真实存在的标题，
 *       且条款号（如 {@code 2.3}）留在正文里——引用回填全靠它；</li>
 *   <li><b>分块 id 确定性且唯一</b>：重建索引要按同一批 id 覆盖，否则重复条款越攒越多；</li>
 *   <li><b>元数据齐备</b>：docId/docTitle/section/file 一个都不能少（缺了就做不了按文档过滤与引用）；</li>
 *   <li><b>不吞内容</b>：真实语料切出来的分块总量与文档量在合理区间，且没有空分块。</li>
 * </ol>
 *
 * <p>顺带断言一条**业务事实**（"补考通过按 60 分计"必须在知识库里能找到）：
 * 这是"语料真的进了知识库"的证据，比"分块数 > 0"有意义得多。
 */
class PolicyChunkerTest {

    private final PolicyChunker chunker = new PolicyChunker();

    private List<Resource> corpus() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:policies/*.md");
        return List.of(resources);
    }

    private String read(Resource resource) throws Exception {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void splitsBySectionAndKeepsClauseNumbers() {
        String markdown = """
                # 成绩构成与绩点换算办法

                ## 2. 总成绩计算
                总成绩 = 平时成绩 × 0.4 + 考试成绩 × 0.6。
                ### 2.3 补考成绩记载
                补考通过的课程，成绩一律按 60 分记载。

                ## 7. 附则
                本办法自发布之日起施行。
                """;

        List<Document> chunks = chunker.chunk("05", markdown, "05-成绩构成与绩点换算办法.md");

        assertEquals(3, chunks.size(), "H2 与 H3 各成一个分块，实际=" + chunks.stream().map(Document::getId).toList());
        assertEquals("05#00", chunks.get(0).getId());
        assertEquals("2. 总成绩计算", chunks.get(0).getMetadata().get("section"));
        assertEquals("成绩构成与绩点换算办法", chunks.get(0).getMetadata().get("docTitle"));
        assertEquals("05", chunks.get(0).getMetadata().get("docId"));
        // 顺序保持原文：## 2. → ### 2.3（H3 独立成块）→ ## 7.
        assertEquals("2.3 补考成绩记载", chunks.get(1).getMetadata().get("section"),
                "H3 应独立成块（与上位条款语义不同），实际=" + chunks.get(1).getMetadata());
        assertTrue(chunks.get(1).getText().contains("60 分"), "条款正文必须保留：" + chunks.get(1).getText());
        assertTrue(chunks.get(1).getText().contains("成绩构成与绩点换算办法"),
                "分块文本要带上文档标题（提升召回）：" + chunks.get(1).getText());
        assertEquals("7. 附则", chunks.get(2).getMetadata().get("section"), "顺序应与原文一致");
    }

    @Test
    void idsAreDeterministicAndUnique() {
        String markdown = "# T\n\n## A\n内容A\n\n## B\n内容B\n";
        List<Document> first = chunker.chunk("09", markdown, "09-x.md");
        List<Document> second = chunker.chunk("09", markdown, "09-x.md");
        assertEquals(first.stream().map(Document::getId).toList(),
                second.stream().map(Document::getId).toList(),
                "同一份文档两次切分必须给出同一批 id（重建索引靠它覆盖）");
        assertEquals(first.size(), new HashSet<>(first.stream().map(Document::getId).toList()).size(),
                "id 不得重复");
    }

    @Test
    void realCorpusIsFullyChunkedWithMetadata() throws Exception {
        List<Resource> files = corpus();
        // 11 份（10 份制度 + README；README 由 PolicyIndexer 跳过，这里只管切分器本身）
        assertEquals(11, files.size(), "语料文件数变了，检查 pom 的构建期复制：" + files.size());

        int total = 0;
        Set<String> sections = new HashSet<>();
        for (Resource file : files) {
            String name = String.valueOf(file.getFilename());
            String docId = PolicyIndexer.docIdOf(name);
            List<Document> chunks = chunker.chunk(docId == null ? "README" : docId, read(file), name);
            assertFalse(chunks.isEmpty(), name + " 切分后没有任何分块");
            for (Document chunk : chunks) {
                assertFalse(chunk.getText() == null || chunk.getText().isBlank(), "出现空分块：" + chunk.getId());
                for (String key : List.of("docId", "docTitle", "section", "file", "chunkIndex")) {
                    assertTrue(chunk.getMetadata().containsKey(key),
                            chunk.getId() + " 缺少元数据 " + key + "：" + chunk.getMetadata());
                }
                sections.add(String.valueOf(chunk.getMetadata().get("section")));
            }
            total += chunks.size();
        }
        // 语料约 800 行、76 个 H2；分块数落在这个区间内说明既没漏切也没碎切
        assertTrue(total >= 60 && total <= 250, "分块总量异常（既可能漏切也可能碎切）：" + total);
        assertTrue(sections.size() >= 50, "章节数偏少，可能有标题没被识别：" + sections.size());
    }

    /** 业务事实：制度里关于补考与绩点的条款必须真的在知识库里（可被检索到） */
    @Test
    void thePolicyAboutMakeupExamScoringIsPresent() throws Exception {
        List<Document> all = new java.util.ArrayList<>();
        for (Resource file : corpus()) {
            String docId = PolicyIndexer.docIdOf(String.valueOf(file.getFilename()));
            if (docId == null) {
                continue;   // README
            }
            all.addAll(chunker.chunk(docId, read(file), String.valueOf(file.getFilename())));
        }
        boolean found = all.stream().anyMatch(d -> d.getText().contains("60 分")
                && String.valueOf(d.getMetadata().get("docId")).equals("05"));
        assertTrue(found, "知识库里应当有「补考通过按 60 分计」这条（JW-05）——否则制度问答答不出依据");
    }

    @Test
    void readmeIsNotTreatedAsAPolicyDocument() {
        assertEquals("05", PolicyIndexer.docIdOf("05-成绩构成与绩点换算办法.md"));
        assertEquals(null, PolicyIndexer.docIdOf("README.md"));
        assertEquals(null, PolicyIndexer.docIdOf(""));
    }
}
