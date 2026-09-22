package duyell.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 接线（M2 框架化迁移的第一步）。
 *
 * <p><b>为什么先只接 ChatClient</b>：迁移的风险不在"能不能调通模型"，而在"换了框架之后
 * 工具选择、参数形状、确认卡片、审计这些既有契约会不会悄悄变化"。所以第一步只建立
 * 框架入口与最小实测，**不动**现有的手写 {@code OpenAiClient} + {@code AiChatService} 循环
 * （计划 1.4 的注意事项：旧实现要保留一个版本周期作回归对照）。
 *
 * <p>模型来源：{@code spring-ai-starter-model-ollama} 在类路径上时，Spring AI 会自动配置
 * {@code OllamaChatModel}（参数见 {@code spring.ai.ollama.*}）；本类只把它包成
 * {@link ChatClient} 并统一默认系统提示词，供后续多轮对话与工具迁移使用。
 *
 * @author duyell
 */
@Configuration
public class AgentConfig {

    /**
     * Agent 的框架入口。
     *
     * <p>刻意**不设置**默认 system prompt：本项目是分角色的（学生/教师/管理员三套提示词），
     * 由调用方按角色传入，避免在这里藏一份"通用提示词"与既有三套口径打架。
     */
    @Bean
    public ChatClient agentChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
