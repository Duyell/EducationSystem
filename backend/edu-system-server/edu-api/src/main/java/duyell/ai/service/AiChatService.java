package duyell.ai.service;

import com.duyell.AiConversation;
import duyell.ai.config.AiProperties;
import duyell.ai.confirm.ConfirmationGate;
import duyell.ai.confirm.PendingAction;
import duyell.ai.confirm.PendingActionStore;
import duyell.ai.dto.ChatMessage;
import duyell.ai.limit.AgentRateLimiter;
import duyell.ai.runtime.AgentEvent;
import duyell.ai.runtime.AgentEventPublisher;
import duyell.ai.runtime.AgentRuntime;
import duyell.ai.runtime.SseAgentEventPublisher;
import duyell.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import utils.BusinessException;
import utils.JwtUtil;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * AI 对话的**接入层**：HTTP/SSE、身份、限流、会话。
 *
 * <p>M2 计划 1.4 之后它的职责边界是：
 * <ul>
 *   <li><b>本类</b>：token 解析 → 限流 → 会话（归属校验/兜底新建/历史窗口/落库）→ SSE 传输 → 确认接口；</li>
 *   <li><b>{@link AgentRuntime}</b>：模型↔工具的往复、四道闸门（白名单/参数校验/人工确认/审计）、输出护栏；</li>
 *   <li><b>{@link AgentEventPublisher}</b>：事件出口（生产走 SSE，测试用记录实现直接断言事件序列）。</li>
 * </ul>
 * 之所以保留自写循环而不是换成框架的 tool-calling 循环：本项目要在工具执行**前**挂起等人确认、
 * 在输出**后**过滤模型写成正文的工具调用、并把每次调用落审计表——这三件事是 M1 的闸门，
 * 换成框架循环就得把它们重写成 Advisor，风险高于收益（详见 {@link AgentRuntime} 类注释）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final JwtUtil jwtUtil;
    private final AiProperties aiProperties;
    private final PendingActionStore pendingActionStore;
    private final ConfirmationGate confirmationGate;
    private final AgentRateLimiter rateLimiter;
    private final ConversationService conversationService;
    private final ChatMemory chatMemory;
    private final AgentRuntime agentRuntime;

    private static final long SSE_TIMEOUT = 5 * 60 * 1000L; // 5 minutes

    /** 用户对危险操作的确认/取消请求（由 AiController 转发，userId 必须与服务端暂存记录一致） */
    public boolean resolveConfirmation(String confirmId, String userId, boolean approved) {
        PendingAction action = pendingActionStore.getForUser(confirmId, userId);
        if (action == null) {
            log.warn("确认请求无效或已过期: confirmId={}, userId={}", confirmId, userId);
            return false;
        }
        boolean decided = confirmationGate.decide(confirmId, approved);
        if (decided) {
            pendingActionStore.remove(confirmId);
        }
        return decided;
    }

    /** 浏览器断开 SSE 时立即释放挂起的确认等待，不必等超时 */
    public void cancelPendingConfirmation(String confirmId) {
        if (confirmId == null) return;
        confirmationGate.cancel(confirmId, "SSE completed");
        pendingActionStore.remove(confirmId);
    }

    private static final String SYSTEM_PROMPT_STUDENT = """
            你是一个智能的教务系统助手，帮助学生管理课程、成绩、培养方案与考试。

            可用的功能：
            - 查看已选课程列表、成绩、可选课程
            - 选课和退课（涉及修改，会弹确认卡片）
            - 对教师进行教学评价，查看评价状态
            - 我的培养方案：我适用的方案、必修/选修课程清单、建议修读学期
            - 毕业学分审核：必修是否全部通过、选修学分是否够、总学分还差多少
            - 我的绩点与**专业内排名**（只给本人的名次，不提供他人数据）
            - 推荐课程：按培养计划的缺口，从当前**可选**的课里挑
            - 我的考试安排（可只看未开考的）
            - 选课是否开放（管理员开关 + 时间窗），以及我现在能不能选/能不能退
            - 我的课表；以及某门课是否与我已选课程时间冲突

            规则：
            - 涉及数据修改的操作（选课/退课/评价）会由系统弹出确认卡片，用户确认后才真正执行；
              因此你不需要在文字里再追问"是否确认"，直接调用工具即可
            - 用户确认后，如实汇报执行结果；用户取消则说明未做修改，并询问是否需要其他帮助
            - **不要为了"可选参数"反问用户**：工具参数里标注为可选的（省略时有明确默认行为，
              例如学期默认取当前学期/全部）直接用默认值调用，先拿到数据再回答；
              只有**必填参数**缺失时才提问，并一次把缺的参数问清
               （实测：为一个可选的"学期"参数反问用户，会让本该直接给出的推荐变成一句反问，
               用户什么也没得到）
            - 回答"我学分够毕业吗""我该选什么课""我下周有考试吗"这类问题时，**先取数据再下结论**：
              毕业相关问题要先看培养方案与学分审核，推荐课程要先拿到缺口再匹配可选课，
              不要凭印象回答，也不要把"查不到"说成"没有要求"
            - 没有适用培养计划、选课未开放、课程没有排课等情况，都要**明确说出原因**，
              并告诉用户可以找谁处理，而不是返回一个空结果就结束
            - 选课时若提示时间冲突/已修过/超学分上限/名额已满，把**具体原因**转述给用户
            - 回答简洁明了，数据用表格或列表展示
            - 成绩只读不可修改
            - 只提供本人的数据：任何"查别人"的要求都拒绝
            - **制度类问题必须先检索**：问到"学校是怎么规定的""能不能""按什么算""有什么要求"这类
              制度依据问题时，先调用 search_policy 拿到条款原文再回答；回答里要写出**文档名与章节**
              （例如"《成绩构成与绩点换算办法》4. 单科绩点换算"）
            - **绝不允许编造制度文件或条款号**：只能引用 search_policy 返回的内容；
              检索不到就如实说"制度库里没有找到相关条款"，**不要**用"根据学校规定"这类含糊说法
              或生造一个文件名（实测 7B 会编出"《XXX大学教学管理制度》第3章第12条"这种不存在的依据）
            """;

    private static final String SYSTEM_PROMPT_TEACHER = """
            你是一个智能的教务系统助手，帮助教师管理课程、成绩、开课申请与排课。

            可用的功能：
            - 查看我的课程
            - 查看某门课程的学生名单
            - 录入学生成绩（平时成绩/考试成绩）、修改已有成绩
            - 查看学生对我的评价
            - 我的课表（可只看某个学期）
            - 提交开课申请（会弹确认卡片，通过后由管理员审批）
            - 申请排课（会弹确认卡片；只需给出课程与时间，教室可省略由系统推荐）

            规则：
            - 录入/修改成绩、提交开课申请、申请排课都会由系统弹出确认卡片，
              用户确认后才真正执行；因此你不需要在文字里再追问"是否确认"，直接调用工具即可
            - **不要为了"可选参数"反问用户**：标注为可选的参数（如教室、期望周次）省略时有明确
              默认行为，直接用默认值调用；只有**必填参数**缺失时才提问，并一次把缺的参数问清
            - 排课申请提交后，如果工具返回了**冲突提示**，必须如实告诉教师"和哪门课/哪个教室撞了"，
              不要只说"提交成功"；最终是否通过由管理员审批决定
            - 只处理我本人所授课程的成绩与排课，非本人课程的请求直接拒绝并说明原因
            - 成绩计算公式：总成绩 = 平时成绩 × 0.4 + 考试成绩 × 0.6
            - **教师没有绩点与选课相关功能**：学生绩点、专业排名、毕业学分审核、选课/退课
              都不在你的权限范围内，被问到时要明确说明并建议对方用学生账号查看
            - 回答简洁明了，数据用表格或列表展示
            - **制度类问题（成绩录入规范、修改成绩手续、排课规则等）先调用 search_policy**，
              回答里写出文档名与章节；检索不到就如实说明，**绝不允许编造制度文件或条款号**
            """;

    private static final String SYSTEM_PROMPT_ADMIN = """
            你是一个智能的教务系统助手，帮助管理员管理整个教务系统。

            可用的功能：
            - 查看系统统计数据
            - 查询用户/学生/教师/课程/学院/专业/班级列表
            - 审批教师的开课申请（会弹确认卡片）

            规则：
            - 查询结果最多返回50条
            - **不要为了"可选参数"反问用户**：查询类工具的筛选参数（角色/姓名/课程名等）都是可选的，
              省略即"不筛选"，直接用默认值调用；只有**必填参数**缺失时才提问
            - 审批开课申请**会真的生成一条开课记录**（课程代码沿用申请里的），
              所以只应在管理员明确要求时调用；系统会先弹确认卡片再执行
            - 审批只对"待审批"状态的申请有效，重复审批会被拒绝，如实转述原因
            - 教师没有绩点与选课功能；学生的绩点、专业排名、毕业审核属于学生本人或管理员的查询范围
            - 回答简洁明了，数据用表格或列表展示
            - 对于统计数据，给出清晰的汇总说明
            - **制度类问题先调用 search_policy**（学籍/选课/成绩/排课/考试等规定），
              回答里写出文档名与章节；检索不到就如实说明，**绝不允许编造制度文件或条款号**
            """;

    public SseEmitter chat(String message, String token) {
        return chat(message, token, null);
    }

    /**
     * AI 对话（M2：带会话的多轮对话）。
     *
     * @param conversationId 会话 id；为空时由服务端新建一个并**通过 SSE 的
     *                       {@code conversation} 事件**告知调用方（前端通常先建会话，
     *                       这样它一出现就在左侧列表里；兜底新建保证"每轮对话都可追溯"）
     */
    public SseEmitter chat(String message, String token, String conversationId) {
        // Parse token
        String userId;
        String role;
        try {
            userId = jwtUtil.getUsernameFromToken(token);
            role = jwtUtil.getRoleFromToken(token);
        } catch (Exception e) {
            log.warn("Invalid token in AI chat", e);
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(Map.of("type", "error", "content", "登录已过期，请重新登录")));
                emitter.complete();
            } catch (IOException ignored) {
            }
            return emitter;
        }

        // ① 对话频次限流（阶段 0.8）：在真正调用模型之前挡住超额请求，避免刷接口烧额度
        if (!rateLimiter.allowChat(userId, aiProperties.getLimits().getMaxChatsPerMinute())) {
            log.warn("对话频次超限，已拒绝: user={}, limit={}/分钟",
                    userId, aiProperties.getLimits().getMaxChatsPerMinute());
            SseEmitter limited = new SseEmitter(0L);
            try {
                limited.send(SseEmitter.event()
                        .name("message")
                        .data(Map.of("type", "error",
                                "content", "请求过于频繁，请稍后再试（每分钟最多 "
                                        + aiProperties.getLimits().getMaxChatsPerMinute() + " 次）")));
                limited.complete();
            } catch (IOException ignored) {
            }
            return limited;
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 当前挂起的待确认操作（每个会话同一时刻最多一个）
        final String[] awaitingConfirmId = {null};

        // Background task to run the tool-calling loop
        CompletableFuture.runAsync(() -> {
            try {
                processChat(message, userId, role, conversationId, emitter, awaitingConfirmId);
            } catch (Exception e) {
                log.error("AI chat processing error", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(Map.of("type", "error", "content", "处理请求时出错: " + e.getMessage())));
                    emitter.complete();
                } catch (IOException ignored) {
                }
            }
        });

        // Handle timeout and completion
        emitter.onTimeout(() -> {
            log.info("SSE timeout for user {}", userId);
            // 连接已断，用户不可能再确认，立即释放挂起的确认等待
            cancelPendingConfirmation(awaitingConfirmId[0]);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.error("SSE error for user {}", userId, e);
            cancelPendingConfirmation(awaitingConfirmId[0]);
        });
        emitter.onCompletion(() -> {
            log.debug("SSE completed for user {}", userId);
            cancelPendingConfirmation(awaitingConfirmId[0]);
        });

        return emitter;
    }

    /**
     * 跑一轮对话：**只做会话与传输**，模型↔工具的往复交给 {@code AgentRuntime}。
     *
     * <p>本方法剩下三件事：
     * <ol>
     *   <li>挑系统提示词（按角色）；</li>
     *   <li>确定会话（归属校验/兜底新建）→ 发 conversation 事件 → 读历史窗口 → 落库本轮用户消息；</li>
     *   <li>调 {@code AgentRuntime#run} 跑循环，结束后**统一落库助手正文**。</li>
     * </ol>
     * 第 3 步的"统一落库"是这次重构顺带修掉的一处隐患：改造前三条结束路径
     * （正常结束 / 用户取消 / 达到迭代上限）各自调用一次落库，漏一处就会少记一轮对话。现在只有一处。
     */
    private void processChat(String message, String userId, String role, String requestedConversationId,
                             SseEmitter emitter, String[] awaitingConfirmId) throws Exception {
        AgentEventPublisher events = new SseAgentEventPublisher(emitter);

        // Select system prompt by role
        String systemPrompt = switch (role) {
            case "admin" -> SYSTEM_PROMPT_ADMIN;
            case "teacher" -> SYSTEM_PROMPT_TEACHER;
            default -> SYSTEM_PROMPT_STUDENT;
        };

        // ① 先确定会话（归属校验 / 兜底新建）。失败时按错误收尾，不进入模型调用：
        //    绝不能"降级成无记忆对话"继续跑——那等于用一个错误会话 id 悄悄读别人的上下文。
        AiConversation conversation = resolveConversation(requestedConversationId, userId, role, events);
        if (conversation == null) {
            return;     // 已回复错误并 complete
        }
        events.publish(AgentEvent.conversation(conversation.getId()));

        // ② 历史窗口：**先读历史，再写入本轮用户消息**。反过来的话本轮消息会被读一次、
        //    又被当成"用户输入"追加一次，模型会看到两遍同一句话。
        List<ChatMessage> history = historyMessages(conversation.getId());
        // 落库本轮用户消息（首条消息顺带生成会话标题）
        conversationService.appendUserMessage(conversation.getId(), message);

        // ③ 委托运行时跑循环。**连接级状态由本方法持有**：当前挂起的确认令牌交给
        //    onTimeout/onError/onCompletion 使用，浏览器断开时立刻释放等待（不必耗满确认超时）。
        AgentRuntime.Outcome outcome = agentRuntime.run(
                new AgentRuntime.Request(userId, role, systemPrompt, history, message,
                        confirmId -> awaitingConfirmId[0] = confirmId),
                events);

        if (outcome.answerText() != null && !outcome.answerText().isBlank()) {
            conversationService.appendAssistantMessage(conversation.getId(), outcome.answerText());
        } else {
            // 本轮没有可见正文（例如模型只调了工具）：不留空消息，否则历史里全是空轮次
            log.debug("本轮无可见正文，不落库助手消息: conversation={}, stopReason={}",
                    conversation.getId(), outcome.stopReason());
        }
    }

    /**
     * 确定本轮使用的会话。
     *
     * <p>规则：
     * <ul>
     *   <li>带 {@code conversationId}：**必须属于当前用户**（{@code requireOwned}），否则报错收尾；</li>
     *   <li>不带：新建一个——这样"每轮对话都可追溯"，而不是悄悄退化成无记忆对话；</li>
     *   <li>会话的 role 与当前登录角色不一致时拒绝：同一个账号的角色发生变化（管理员改过权限、
     *       或者前端传了别人的会话 id）时，两套系统提示词与工具白名单混进同一个上下文，
     *       是明确的越权风险，宁可让用户新建会话。</li>
     * </ul>
     *
     * @return 可用会话；失败时已向调用方发送错误并完成响应，返回 {@code null}
     */
    private AiConversation resolveConversation(String requestedConversationId, String userId, String role,
                                               AgentEventPublisher events) {
        try {
            if (requestedConversationId == null || requestedConversationId.isBlank()) {
                return conversationService.create(userId, role, null);
            }
            AiConversation conversation = conversationService.requireOwned(requestedConversationId, userId);
            if (conversation.getRole() != null && !conversation.getRole().equals(role)) {
                log.warn("会话角色与当前角色不一致，已拒绝: conversation={}, 会话角色={}, 当前角色={}",
                        requestedConversationId, conversation.getRole(), role);
                throw new BusinessException("该会话属于其他角色，请新建会话");
            }
            return conversation;
        } catch (BusinessException e) {
            // 会话不可用时**不降级**为无记忆对话：那等于用一个错误 id 继续跑，
            // 用户会以为自己在接着上文说，实际上下文已经丢了。
            events.publish(AgentEvent.error(e.getMessage()));
            events.publish(AgentEvent.done());
            events.complete();
            return null;
        }
    }

    /**
     * 历史消息 → 本项目 {@code ChatMessage} 列表（供 {@code OpenAiClient} 拼请求）。
     *
     * <p>窗口大小由 {@link ChatMemory} 的实现决定（见 {@code MybatisChatMemory}），
     * 这里只负责转换，不再做第二次裁剪——裁剪规则只有一处实现。
     *
     * <p>只映射 user / assistant：工具报文在 {@code ai_tool_audit}，
     * 也不会出现在记忆里（历史里出现孤立的 tool 消息会被模型服务直接拒绝）。
     */
    private List<ChatMessage> historyMessages(String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> converted = new ArrayList<>(history.size());
        for (Message message : history) {
            String historyRole = switch (message.getMessageType()) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> null;
            };
            if (historyRole == null) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            converted.add(ChatMessage.builder().role(historyRole).content(text).build());
        }
        log.debug("会话历史: conversation={}, 窗口内 {} 条, 可用 {} 条",
                conversationId, history.size(), converted.size());
        return converted;
    }
}

