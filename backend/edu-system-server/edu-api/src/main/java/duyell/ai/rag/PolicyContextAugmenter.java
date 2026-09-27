package duyell.ai.rag;

import duyell.ai.runtime.ContextAugmenter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * **制度问题的路由级强制检索**（M3 收尾）：识别制度意图 → 服务端自动检索 → 把条款注入系统提示词。
 *
 * <p><b>为什么不让模型自己决定检索</b>：实测 7B 在制度类问题上会跳过检索工具（见 {@link ContextAugmenter}
 * 的类注释），而"编造制度依据"对教务问答是**致命**的——用户会照着假条款办事。
 * 因此这里改成服务端的确定性行为：只要问题看起来是制度询问，就先把条款查出来塞进上下文，
 * 模型想不看到都难，也就没有"凭常识编"的空间。
 *
 * <p><b>关键词判定的取舍：宁可多注入，不可漏注入</b>。漏注入 = 回到"模型编造依据"的老问题；
 * 多注入 = 多花一点上下文，且注进来的都是真实条款（无害）。所以词表偏保守（覆盖"规定/能不能/怎么算/
 * 依据"这类**询问规则**的说法），并刻意**不**把"成绩/绩点/学分"这类名词单列——
 * 否则"我绩点多少"这种个人数据查询也会被注入一堆制度条款。
 *
 * <p><b>失败必须静默</b>：向量库连不上/检索报错时，只记日志并返回 null，
 * 绝不把一次正常对话变成错误页（依据少一份可以接受，整个请求失败不可以）。
 *
 * @author duyell
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
public class PolicyContextAugmenter implements ContextAugmenter {

    /**
     * "像是在问制度"的判断词表。
     *
     * <p>覆盖三类说法：① 直接指向规则本体（规定/制度/政策/办法/规则/标准/依据/条款）；
     * ② 询问许可与算法（能不能/可以吗/是否允许/需要什么/怎么算/如何/流程/手续/上限/条件）；
     * ③ **问句形式**（怎么/为什么/哪些/吗）——这一条是被实测逼出来的：
     * "退课之后还能再选这门课吗"不含前两类任何词，却是不折不扣的制度询问。
     *
     * <p>刻意**不**收录"成绩/绩点/学分/课表"这类名词：它们大量出现在个人数据查询里
     * （"我绩点多少"），收了会把制度条款塞进查数据的对话。
     */
    private static final Pattern POLICY_INTENT = Pattern.compile(
            "规定|制度|政策|办法|规则|标准|依据|条款|"
                    + "能不能|可不可以|可以吗|是否允许|是否可|允许吗|需要什么|需要哪些|要什么手续|"
                    + "怎么算|如何计算|怎么计算|怎么定|如何判定|怎么判定|"
                    + "流程|手续|上限|下限|条件|要求|"
                    + "怎么|为什么|为何|哪些|什么情况|吗|"
                    + "需要多少|要多少|多少学分|学业预警|毕业");

    /** 追加进上下文的条款条数（比工具调用的默认 topK 小：注进系统提示词要省着用） */
    private final int augmentTopK;
    private final PolicySearchService policySearchService;

    public PolicyContextAugmenter(PolicySearchService policySearchService,
                                  @Value("${ai.rag.augment-top-k:3}") int augmentTopK) {
        this.policySearchService = policySearchService;
        this.augmentTopK = Math.max(1, augmentTopK);
    }

    @Override
    public String augmentFor(String userMessage) {
        if (!StringUtils.hasText(userMessage) || !looksLikePolicyQuestion(userMessage)) {
            return null;
        }
        try {
            List<PolicySearchService.PolicyHit> hits = policySearchService.search(userMessage, null, augmentTopK);
            if (hits.isEmpty()) {
                // 检索不到也要**明确告诉模型"没有依据"**：否则它又会用常识补一段出来
                log.info("制度意图识别命中但未召回条款，注入『无依据』提示: query={}", userMessage);
                return """
                        【制度库检索结果】系统已按你的问题检索校内制度库，**没有找到相关条款**。
                        因此：如果这个问题属于学校制度/规定范畴，请如实回答"制度库里没有找到相关条款"，
                        **不要**用常识推测、**不要**引用任何文件名或条款号；其余问题正常回答。""";
            }
            StringBuilder builder = new StringBuilder();
            builder.append("【制度库检索结果】系统已按用户问题检索校内制度库，以下为命中的**真实条款**")
                    .append("（共 ").append(hits.size()).append(" 条）。\n")
                    .append("回答制度类问题时：**必须只依据下面这些条款**，并注明出处（文档名 + 章节）；")
                    .append("**不得**引用下面没有出现的文件名或条款号。\n\n");
            int index = 1;
            for (PolicySearchService.PolicyHit hit : hits) {
                builder.append(index++).append(". 【").append(hit.citation()).append("】\n")
                        .append(hit.text()).append("\n\n");
            }
            log.info("制度意图识别命中，已注入条款: query={}, 条数={}", userMessage, hits.size());
            return builder.toString().trim();
        } catch (Exception e) {
            // 增强失败绝不能让对话失败：少一份依据可以接受，报错页不可以
            log.warn("制度条款注入失败（本次对话将不带制度依据）: query={}, 原因={}", userMessage, e.getMessage());
            return null;
        }
    }

    /** 是否像在问制度（对判定口径不放心时，可直接用单测喂句子核对） */
    boolean looksLikePolicyQuestion(String message) {
        return POLICY_INTENT.matcher(message).find();
    }
}
