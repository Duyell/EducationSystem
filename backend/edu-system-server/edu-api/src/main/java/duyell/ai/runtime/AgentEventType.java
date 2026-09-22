package duyell.ai.runtime;

/**
 * Agent 与前端之间的**事件契约**（M2 计划 1.4）。
 *
 * <p>把原来散落在 {@code AiChatService} 里的一堆字符串（{@code "token"} / {@code "confirm"} …）
 * 收成一个枚举，原因不是"好看"，而是它现在是**对外契约**：
 * 前端 `useAgentChat` 的事件分支、`.dsh` 下的评测与验证脚本、以及 SSE 抓包排查，
 * 全都在按这些名字判断。字符串写错一个字母不会有任何编译错误，只会表现为"某类事件永远收不到"。
 *
 * <p>序列化时的 {@code type} 字段一律用 {@link #wireName()}（枚举名小写），
 * 以保证与既有线协议逐字一致：{@code confirm_result} 这种带下划线的名字就是这么来的。
 *
 * @author duyell
 */
public enum AgentEventType {

    /** 模型流式输出的**可见正文**（可能被输出护栏扣留后补发） */
    TOKEN,
    /** 过程状态提示（"正在执行: 选课"、"等待确认"、"参数不完整"…）。当前前端不展示，但排查时要看 */
    STATUS,
    /** 本轮对话归属的会话 id（流的**第一个**事件，服务端可能因此兜底新建了会话） */
    CONVERSATION,
    /** 危险操作挂起，等用户在前端确认（HITL） */
    CONFIRM,
    /** 用户对确认卡片的决定（含超时/失效） */
    CONFIRM_RESULT,
    /** 错误（越权工具、模型服务报错、会话不可用…） */
    ERROR,
    /** 本轮结束（服务端已收尾，前端可解除 loading） */
    DONE;

    /** 线协议里的 {@code type} 值（枚举名小写，保持与历史一致） */
    public String wireName() {
        return name().toLowerCase();
    }
}
