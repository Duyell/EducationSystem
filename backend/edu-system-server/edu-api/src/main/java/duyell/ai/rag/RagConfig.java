package duyell.ai.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

/**
 * M3（RAG 制度问答）的向量库装配。
 *
 * <p><b>为什么是手工装配，而不是让 Spring AI 自动配置</b>（这是接入 pgvector 最容易踩的坑）：
 * pgvector 的自动配置以 {@code spring.ai.vectorstore.type=pgvector} 为条件，
 * 一旦开启，它会拿**应用的主 DataSource** 去建扩展与向量表——而本项目的主库是 MySQL，
 * 没有 {@code vector} 类型（实测：`create temporary table (v vector(3))` 报 ERROR 1064）。
 * 结果是"为了加个 RAG，整个应用起不来"。
 *
 * <p>因此这里的做法是：
 * <ol>
 *   <li>{@code application.yml} 里**不设** {@code spring.ai.vectorstore.type} → 自动配置整体退让；</li>
 *   <li>向量库指向一个**独立的 PostgreSQL 数据源**（{@code ai.rag.*}），与 MySQL 互不相干；</li>
 *   <li>整个配置由 {@code ai.rag.enabled} 开关控制，**默认关闭**：
 *       关闭时应用完全不碰 PostgreSQL，既有 278 项测试与启动流程零影响。</li>
 * </ol>
 *
 * <p>代价是"检索代码依赖的是 Spring AI 的 {@link VectorStore} 抽象"这件事必须守住：
 * 将来要换回内存版（{@code SimpleVectorStore}）或换别的库，只改本类，
 * 检索与工具代码一行不动——这也是当初选 pgvector 时说好的"可替换"。
 *
 * @author duyell
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
public class RagConfig {

    /**
     * 向量库专用数据源（PostgreSQL）。
     *
     * <p>刻意**不**把它标成 {@code @Primary}、也不放进 Spring 的 {@code DataSource} 自动装配路径：
     * 主库仍是 MySQL，MyBatis 与业务事务必须继续走原来的数据源。
     */
    @Bean(name = "ragDataSource", destroyMethod = "close")
    public HikariDataSource ragDataSource(
            @Value("${ai.rag.jdbc-url}") String jdbcUrl,
            @Value("${ai.rag.username}") String username,
            @Value("${ai.rag.password}") String password) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setPoolName("rag-pgvector-pool");
        // 向量库是旁路能力：连不上 PostgreSQL 时不该拖垮业务查询，因此池子开小、超时短
        dataSource.setMaximumPoolSize(4);
        dataSource.setConnectionTimeout(5000);
        return dataSource;
    }

    @Bean(name = "ragJdbcTemplate")
    public JdbcTemplate ragJdbcTemplate(@Qualifier("ragDataSource") HikariDataSource ragDataSource) {
        return new JdbcTemplate(ragDataSource);
    }

    /**
     * 制度知识库的向量库。
     *
     * @param embeddingModel 由 {@code spring-ai-starter-model-ollama} 自动配置（本机 bge-m3，1024 维）
     */
    @Bean(name = "policyVectorStore")
    public VectorStore policyVectorStore(@Qualifier("ragJdbcTemplate") JdbcTemplate ragJdbcTemplate,
                                        EmbeddingModel embeddingModel,
                                        @Value("${ai.rag.table-name}") String tableName,
                                        @Value("${ai.rag.dimensions}") int dimensions) {
        return PgVectorStore.builder(ragJdbcTemplate, embeddingModel)
                .vectorTableName(tableName)
                // 维度必须与嵌入模型一致（bge-m3=1024）；不一致会在写入时报维度错误
                .dimensions(dimensions)
                // id 用 TEXT 而不是默认的 UUID：语料分块的 id 要**确定性且可读**
                // （形如 `05-成绩构成与绩点换算办法#2.3#01`），重建索引时才能按同一批 id 覆盖，
                // 否则每次重建都会插入一批新的 UUID 分块，检索结果里全是重复条款。
                // （这个选择是 RagConfigTest 报 "Invalid UUID string" 时才暴露出来的。）
                .idType(PgVectorStore.PgIdType.TEXT)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                // 首次启动自动建扩展/表/索引，省掉手工 DDL（生产可关掉，改由管理接口触发）
                .initializeSchema(true)
                .build();
    }
}
