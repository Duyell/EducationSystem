package duyell.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 的**默认状态**测试：关着的时候，应用一点都不该碰 PostgreSQL。
 *
 * <p>为什么这条值得单独立一个测试：pgvector 的自动配置以 {@code spring.ai.vectorstore.type} 为条件，
 * 而它一旦生效就会拿**主数据源（MySQL）**去建 vector 表——MySQL 没有 vector 类型，
 * 结果是"为了加 RAG，整个应用起不来"。这条断言把"默认关闭"从一句注释变成可执行的约束：
 * 以后谁顺手在 yml 里加上 {@code spring.ai.vectorstore.type: pgvector}，这里立刻变红。
 *
 * <p>它用与其它 {@code @SpringBootTest(NONE)} 测试类相同的上下文配置，
 * 因此复用缓存的 Spring 上下文，几乎不增加测试时间。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RagDisabledByDefaultTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void noVectorStoreIsWiredUnlessExplicitlyEnabled() {
        // ai.rag.enabled 默认为 false（application.yml），因此不该有向量库 Bean
        assertTrue(applicationContext.getBeansOfType(VectorStore.class).isEmpty(),
                "默认（ai.rag.enabled=false）不该存在任何 VectorStore Bean："
                        + applicationContext.getBeansOfType(VectorStore.class).keySet());

        // 也不能有指向 PostgreSQL 的 RAG 数据源
        assertTrue(applicationContext.getBeansOfType(RagConfig.class).isEmpty()
                        || applicationContext.getBeanNamesForType(RagConfig.class).length == 0,
                "RagConfig 在关闭状态下不应被装配");
    }
}
