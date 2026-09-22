package duyell.ai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 制度知识库索引（ETL 第二步：切分 → 嵌入 → 写向量库）。
 *
 * <p><b>幂等是硬要求</b>：语料还在逐章审计修订（JW-xx 会改），所以要能反复重建索引而不产生重复条款。
 * 做法是每个文档先按元数据删除旧分块（`docId == '05'`），再写入新分块——
 * 配合 {@link PolicyChunker} 的确定性 id，重建后库里同一份文档只会有最新一份。
 * 只按 id 覆盖是不够的：文档变短时，旧的尾部高分块不会被覆盖，会变成"幽灵条款"留在检索结果里。
 *
 * <p><b>启动即建索引</b>由 {@code ai.rag.reindex-on-startup} 控制（默认开）。
 * 索引失败**不让应用起不来**：教务业务才是主链路，RAG 是旁路能力；
 * 这里记 ERROR 日志（含原因）后继续启动，检索时若索引为空会明确回答"没有找到依据"
 * ——比"整个系统起不来"和"编一个答案"都好。
 *
 * @author duyell
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
public class PolicyIndexer {

    /** 语料在 classpath 下的位置（由 edu-api/pom.xml 在构建期从 docs/policies 复制而来） */
    private static final String CORPUS_PATTERN = "classpath:policies/*.md";

    private final PolicyChunker chunker;
    private final VectorStore policyVectorStore;
    private final boolean reindexOnStartup;

    /**
     * 显式写构造器而不用 {@code @RequiredArgsConstructor}：
     * Lombok 不会把字段上的 {@code @Qualifier}/{@code @Value} 复制到构造参数上，
     * 于是 "final 字段 + @Value" 会变成 Spring 去找一个 boolean Bean（启动即失败），
     * 而 {@code @Qualifier} 也会被静默忽略。这两个坑都很隐蔽，干脆手写清楚。
     */
    public PolicyIndexer(PolicyChunker chunker,
                        @Qualifier("policyVectorStore") VectorStore policyVectorStore,
                        @Value("${ai.rag.reindex-on-startup:true}") boolean reindexOnStartup) {
        this.chunker = chunker;
        this.policyVectorStore = policyVectorStore;
        this.reindexOnStartup = reindexOnStartup;
    }

    /**
     * 重建全部索引。
     *
     * @return 写入的分块数
     */
    public int reindex() {
        List<Resource> files = corpusFiles();
        int total = 0;
        for (Resource file : files) {
            String fileName = file.getFilename() == null ? "" : file.getFilename();
            String docId = docIdOf(fileName);
            if (docId == null) {
                // README 是语料的使用说明，不是制度本身，不进知识库（否则会被当条款召回）
                log.debug("跳过非制度文档: {}", fileName);
                continue;
            }
            String content = read(file);
            if (content == null || content.isBlank()) {
                log.warn("制度文档为空，已跳过: {}", fileName);
                continue;
            }
            List<Document> chunks = chunker.chunk(docId, content, fileName);
            if (chunks.isEmpty()) {
                log.warn("制度文档切分后没有任何分块: {}", fileName);
                continue;
            }
            // 先清旧分块再写新的：文档变短时不会留下"幽灵条款"
            policyVectorStore.delete("docId == '" + docId + "'");
            policyVectorStore.add(chunks);
            total += chunks.size();
            log.info("制度文档已索引: {} -> {} 个分块（{} ~ {}）",
                    fileName, chunks.size(), chunks.get(0).getId(), chunks.get(chunks.size() - 1).getId());
        }
        log.info("制度知识库索引完成: {} 份文档 / {} 个分块", files.size(), total);
        return total;
    }

    /** 应用就绪后按需建索引（关闭时不建，便于测试与"只读已有索引"的部署） */
    @EventListener(ApplicationReadyEvent.class)
    public void reindexOnStartupIfEnabled() {
        if (!reindexOnStartup) {
            log.info("ai.rag.reindex-on-startup=false，跳过启动建索引（沿用库中已有索引）");
            return;
        }
        try {
            int chunks = reindex();
            if (chunks == 0) {
                log.warn("制度知识库索引为空：检索工具会明确回答「找不到依据」，请检查语料是否随构建进 jar");
            }
        } catch (Exception e) {
            // 旁路能力失败不该拖垮教务业务：记清楚原因，让检索侧如实回答"没有依据"
            log.error("制度知识库建索引失败（RAG 将不可用，但业务功能不受影响）: {}", e.getMessage(), e);
        }
    }

    private List<Resource> corpusFiles() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(CORPUS_PATTERN);
            List<Resource> files = new ArrayList<>(List.of(resources));
            files.sort((a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
            if (files.isEmpty()) {
                log.warn("classpath 下没有找到制度语料（{}）：检查 pom 的 resources 复制是否生效", CORPUS_PATTERN);
            }
            return files;
        } catch (Exception e) {
            throw new IllegalStateException("读取制度语料失败: " + CORPUS_PATTERN, e);
        }
    }

    private String read(Resource resource) {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取制度语料失败: {}", resource.getFilename(), e);
            return null;
        }
    }

    /** 文件名前缀即文档编号（`05-成绩构成与绩点换算办法.md` → {@code 05}）；非 `数字-` 开头返回 null */
    static String docIdOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        int dash = fileName.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        String prefix = fileName.substring(0, dash);
        return prefix.chars().allMatch(Character::isDigit) ? prefix : null;
    }
}

