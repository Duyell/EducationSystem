package duyell.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制度文档切分（M3 的 ETL 第一步）。
 *
 * <p><b>切分策略：按章节切，而不是按固定长度切</b>。制度文档的"最小完整语义单元"就是一条条款
 * （`## 2. 总成绩计算`），按字数硬切会把一条规则劈成两半，检索回来的是半句话——
 * 这是 RAG 里最常见的"检索到了但答不对"的成因。实测语料非常规整：每份 1 个 H1 标题 +
 * 6~9 个带编号的 H2 + 最多 1 个 H3，因此"每个 H2/H3 一个分块"就能覆盖全部内容。
 *
 * <p>两条刻意的设计：
 * <ol>
 *   <li><b>分块文本带上文档标题</b>（`【成绩构成与绩点换算办法 / 4. 单科绩点换算】`）：
 *       条款正文里往往只说"本条规定…"，不带标题时向量语义是残缺的，
 *       用户问"绩点怎么算"很可能召不回这些条款；带上标题后召回率明显更稳。</li>
 *   <li><b>id 确定性可读</b>（`05#03`，见 {@link #chunkId}）：重建索引时按同一批 id 覆盖，
 *       而不是每次插入一批新 UUID 把重复条款越攒越多。</li>
 * </ol>
 *
 * <p>超长章节（超过 {@link #MAX_CHARS}）才退化为按长度切并带 overlap —— 语料里目前没有，
 * 但语料会增补（JW-xx 还在审计修订），留这条路比"以后不会超长"这种假设可靠。
 *
 * @author duyell
 */
@Component
public class PolicyChunker {

    /** 单个分块的字符上限：超过就按段落 + overlap 再切 */
    static final int MAX_CHARS = 1200;
    /** 长度切分时的重叠字符数，避免把跨段落的句子切断后两边都读不通 */
    static final int OVERLAP_CHARS = 120;

    /**
     * 切分一份制度文档。
     *
     * @param docId   文档编号（取自文件名前缀，如 {@code 05}）
     * @param content markdown 原文
     * @param file    文件名（元数据里留痕，便于回溯到仓库里的哪一份）
     */
    public List<Document> chunk(String docId, String content, String file) {
        String docTitle = firstHeading(content);
        List<Section> sections = splitByHeading(content);
        List<Document> documents = new ArrayList<>();
        int index = 0;
        for (Section section : sections) {
            for (String part : splitLongSection(section.body())) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("docId", docId);
                metadata.put("docTitle", docTitle);
                metadata.put("section", section.heading());
                metadata.put("file", file);
                metadata.put("chunkIndex", index);

                String text = "【" + docTitle + " / " + section.heading() + "】\n" + part;
                documents.add(new Document(chunkId(docId, index), text, metadata));
                index++;
            }
        }
        return documents;
    }

    /** 分块 id：`{docId}#{序号}`，零填充保证字典序与文档顺序一致，便于人读与日志排查 */
    static String chunkId(String docId, int index) {
        return String.format("%s#%02d", docId, index);
    }

    private record Section(String heading, String body) {
    }

    private String firstHeading(String content) {
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "制度文档";
    }

    /**
     * 按 `##` / `###` 标题切分。
     *
     * <p>`###` 也独立成块：语料里的 H3 是"与系统实现的关系（供审计）"这类**与上位条款语义不同**
     * 的内容（一条讲制度要求，一条讲系统怎么做的），混在一起会让检索结果答非所问。
     */
    private List<Section> splitByHeading(String content) {
        List<Section> sections = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();

        for (String line : content.split("\n", -1)) {
            boolean isHeading = line.startsWith("## ") || line.startsWith("### ");
            if (isHeading) {
                if (heading != null && !body.toString().isBlank()) {
                    sections.add(new Section(heading, body.toString().trim()));
                }
                heading = line.replaceFirst("^#{2,3} ", "").trim();
                body.setLength(0);
            } else if (heading != null && !line.startsWith("# ")) {
                body.append(line).append('\n');
            }
        }
        if (heading != null && !body.toString().isBlank()) {
            sections.add(new Section(heading, body.toString().trim()));
        }
        return sections;
    }

    /** 短章节原样返回（绝大多数情况）；超长章节按段落聚合到 MAX_CHARS 并保留 overlap 尾巴 */
    private List<String> splitLongSection(String body) {
        if (body.length() <= MAX_CHARS) {
            return List.of(body);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : body.split("\n\n")) {
            if (current.length() > 0 && current.length() + paragraph.length() > MAX_CHARS) {
                parts.add(current.toString().trim());
                String tail = current.length() > OVERLAP_CHARS
                        ? current.substring(current.length() - OVERLAP_CHARS) : current.toString();
                current.setLength(0);
                current.append(tail.trim()).append("\n\n");
            }
            current.append(paragraph).append("\n\n");
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }
}
