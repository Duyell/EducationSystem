package duyell.ai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring AI 接线测试（M2 第一步）。
 *
 * <p>分两层，刻意分开：
 * <ol>
 *   <li><b>接线是否成立</b>（默认跑）：ChatClient / ChatModel / ChatMemory 这些 Bean 在不在。
 *       它不需要模型在线——Spring AI 的 Bean 是懒连接的，因此可以进常规测试套件；</li>
 *   <li><b>真能调到模型</b>（默认**不跑**）：用 {@code -Dai.live=true} 显式打开。
 *       真实模型调用又慢又依赖本机 Ollama，放进默认套件会让"测试全绿"变成一个碰运气的事
 *       （本项目已多次栽在"外部依赖没起导致误判"上）。</li>
 * </ol>
 *
 * <p>手动跑一次真实调用：
 * <pre>
 * mvn -o -B test -pl edu-api -am -Dtest=SpringAiWiringTest -Dai.live=true
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SpringAiWiringTest {

    @Autowired
    private ChatClient agentChatClient;

    @Autowired
    private ChatModel chatModel;

    @Autowired(required = false)
    private ChatMemory chatMemory;

    @Test
    void frameworkBeansAreWired() {
        assertNotNull(agentChatClient, "Spring AI 的 ChatClient 未接线（检查 AgentConfig 与 spring.ai.ollama 配置）");
        assertNotNull(chatModel, "Spring AI 的 ChatModel 未自动配置（检查 spring-ai-starter-model-ollama 依赖）");
        // ChatMemory 由 spring-ai-autoconfigure-model-chat-memory 提供；这里只记录它是否可用，
        // 不强制要求——多轮记忆的落地方案见 M2 后续步骤
        System.out.println("[wiring] ChatModel = " + chatModel.getClass().getSimpleName()
                + ", ChatMemory = " + (chatMemory == null ? "未提供" : chatMemory.getClass().getSimpleName()));
    }

    /** 真实调用一次本机 Ollama（默认关闭，见类注释） */
    @Test
    @EnabledIfSystemProperty(named = "ai.live", matches = "true")
    void liveCallThroughSpringAi() {
        String answer = agentChatClient.prompt()
                .user("只回复两个字：收到")
                .call()
                .content();

        System.out.println("[live] Spring AI 回答 = " + answer);
        assertNotNull(answer, "模型应返回内容");
        assertFalse(answer.isBlank(), "模型返回了空白内容");
        assertTrue(answer.length() < 200, "这种简单提示不该产生长回答：" + answer);
    }
}
