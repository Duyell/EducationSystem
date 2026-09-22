package duyell.ai.runtime;

/**
 * 事件出口（M2 计划 1.4 的解耦点）。
 *
 * <p><b>为什么要这层抽象</b>：改造前 {@code AiChatService} 直接持有 {@code SseEmitter}，
 * 于是"跑一轮 Agent"与"通过 HTTP 推事件"绑死在一起——想测一轮对话的**事件序列**
 * （比如"被拒绝的确认之后必须先发取消状态、再发 done、且绝不能继续执行工具"）
 * 就只能起真 HTTP 连接、解析 SSE 文本。
 *
 * <p>抽出这一层之后：生产用 {@link SseAgentEventPublisher} 推给浏览器，
 * 测试用"记录型"实现直接断言事件序列，两者跑的是**同一段编排代码**（`AgentRuntime`）——
 * 这是"只保留编排"这个决定的直接收益。
 *
 * @author duyell
 */
public interface AgentEventPublisher {

    /** 发布一个事件（实现方负责序列化与传输） */
    void publish(AgentEvent event);

    /** 本轮结束：关闭输出通道（生产实现会 complete SSE） */
    void complete();
}
