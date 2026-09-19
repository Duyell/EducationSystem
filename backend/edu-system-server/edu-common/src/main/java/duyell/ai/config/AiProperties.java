package duyell.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o-mini";

    /** 危险操作人工确认（HITL）相关配置 */
    private Confirmation confirmation = new Confirmation();

    /** 用量与预算闸门（阶段 0.8） */
    private Limits limits = new Limits();

    @Data
    public static class Confirmation {
        /** 是否开启危险操作确认。关闭仅用于本地调试，生产必须为 true */
        private boolean enabled = true;
        /** 暂存待确认操作的 TTL（秒），也是等待用户响应的超时时间 */
        private int timeoutSeconds = 180;
    }

    @Data
    public static class Limits {
        /** 每用户每分钟最多发起的对话次数；<=0 表示不限制 */
        private int maxChatsPerMinute = 10;
        /** 单次对话内允许的最大工具调用轮数（原为硬编码 10） */
        private int maxIterations = 5;
        /** 单次对话内允许的最大工具调用总次数；<=0 表示不限制 */
        private int maxToolCallsPerChat = 20;
    }
}
