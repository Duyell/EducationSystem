package duyell.ai.rag;

import duyell.ai.runtime.AugmentedContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制度问题**路由级强制检索**的单元测试（纯逻辑：不需要数据库、向量库、嵌入模型、模型）。
 *
 * <p>它盯的是这次修复的核心不变量：**制度类问题一定要带上真实条款（或明确的"没有依据"），
 * 不制度类的问题一定不要被塞一堆无关条款**。这两条以前全靠提示词乞求模型配合，现在由服务端保证。
 */
class PolicyContextAugmenterTest {

    /** 用匿名子类顶掉真实检索：本测试只验证"判定 + 组装 + 失败降级"这三段逻辑 */
    private PolicyContextAugmenter augmenterReturning(List<PolicySearchService.PolicyHit> hits) {
        PolicySearchService stub = new PolicySearchService((VectorStore) null, 5, 0.35) {
            @Override
            public List<PolicyHit> search(String query, String docId, Integer topK) {
                return hits;
            }
        };
        return new PolicyContextAugmenter(stub, 3);
    }

    private PolicyContextAugmenter augmenterFailing() {
        PolicySearchService stub = new PolicySearchService((VectorStore) null, 5, 0.35) {
            @Override
            public List<PolicyHit> search(String query, String docId, Integer topK) {
                throw new IllegalStateException("向量库连不上");
            }
        };
        return new PolicyContextAugmenter(stub, 3);
    }

    private static final PolicySearchService.PolicyHit HIT = new PolicySearchService.PolicyHit(
            "03", "重修与补考办法", "3. 补考",
            "补考通过的课程，成绩一律按 60 分记载。",
            "重修与补考办法 3. 补考");

    @Test
    void policyQuestionGetsTheRealClauseInjectedWithCitations() {
        AugmentedContext context = augmenterReturning(List.of(HIT)).augmentFor("补考通过以后绩点怎么算");

        assertTrue(context.hasText(), "制度问题必须注入文本");
        assertTrue(context.text().contains("【制度库检索结果】"), context.text());
        assertTrue(context.text().contains("重修与补考办法 3. 补考"), "必须带出处：" + context.text());
        assertTrue(context.text().contains("按 60 分记载"), "必须带条款原文：" + context.text());
        assertTrue(context.text().contains("不得") && context.text().contains("文件名"),
                "必须明确禁止引用未出现的文件名（这正是之前编造文件名的漏洞）：" + context.text());
    }

    /**
     * **注入路径也要给出来处**：这条以前缺失——服务端替模型检索完，用户界面上却没有任何依据可看，
     * 与"模型凭空作答"长得一模一样。字段名必须与工具结果逐字一致，否则前端一套渲染逻辑接不上。
     */
    @Test
    void injectedClausesCarrySourcesForTheUi() {
        AugmentedContext context = augmenterReturning(List.of(HIT)).augmentFor("补考通过以后绩点怎么算");

        assertEquals(1, context.sources().size(), "命中 1 条就该有 1 条出处");
        Map<String, Object> source = context.sources().get(0);
        assertEquals("03", source.get("docId"));
        assertEquals("重修与补考办法", source.get("docTitle"));
        assertEquals("3. 补考", source.get("section"));
        assertEquals("重修与补考办法 3. 补考", source.get("citation"));
    }

    /** 零召回时也要给模型一句"没有依据"，否则它又会用常识补一段出来 */
    @Test
    void emptyRetrievalStillTellsTheModelThereIsNoBasis() {
        AugmentedContext context = augmenterReturning(List.of()).augmentFor("学校对转专业有什么规定");

        assertTrue(context.text().contains("没有找到相关条款"), context.text());
        assertTrue(context.text().contains("不要") && context.text().contains("引用"), context.text());
        // 没有条款就不该有来源卡片：空卡片比没有卡片更糟（看着像"依据为空"）
        assertTrue(context.sources().isEmpty(), "零召回不能出来源：" + context.sources());
    }

    @Test
    void nonPolicyQuestionsAreNotAugmented() {
        PolicyContextAugmenter augmenter = augmenterReturning(List.of(HIT));
        // 个人数据查询：不该被塞制度条款（否则既费上下文又可能答偏）
        assertNull(augmenter.augmentFor("我绩点是多少").text());
        assertNull(augmenter.augmentFor("帮我选课，课程ID是5").text());
        assertNull(augmenter.augmentFor("我的课表").text());
        assertNull(augmenter.augmentFor("").text());
        assertNull(augmenter.augmentFor(null).text());
    }

    @Test
    void commonPolicyPhrasingsAreRecognised() {
        PolicyContextAugmenter augmenter = augmenterReturning(List.of(HIT));
        for (String question : List.of(
                "退课之后还能再选这门课吗", "毕业需要多少学分", "考试作弊怎么处理",
                "重修要交钱吗", "老师改成绩需要什么手续", "排课时间冲突怎么判定",
                "评教会让老师知道是谁评的吗", "什么情况下会被学业预警")) {
            assertNotNull(augmenter.augmentFor(question).text(), "应识别为制度问题: " + question);
        }
    }

    /** 检索失败必须降级为"不注入"，绝不把一次正常对话变成错误页 */
    @Test
    void retrievalFailureDegradesSilently() {
        AugmentedContext context = augmenterFailing().augmentFor("补考通过以后绩点怎么算");
        assertNull(context.text());
        assertTrue(context.sources().isEmpty());
    }
}
