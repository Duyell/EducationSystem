package duyell.ai.controller;

import com.duyell.AiConversation;
import com.duyell.AiMessage;
import duyell.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import utils.Result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 会话管理接口（M2 多轮记忆）。
 *
 * <p><b>权限</b>：{@code /ai/**} 不设角色限制（三个角色都能用 AI 助手），
 * 但**归属由服务端强制**——{@link ConversationService#requireOwned} 是所有按 id 读写的唯一入口，
 * 用户身份一律取自 token（{@code request.getAttribute("username")}），不接受入参。
 * 因此改 URL 里的会话 id 只会得到"会话不存在或无权访问"，而不是别人的对话内容。
 *
 * <p><b>为什么 role 不从请求体取</b>：请求体是客户端可改的。若允许前端指定 role，
 * 学生账号就能拿到教师那套（含成绩录入等工具）的会话角色。role 只认 token。
 *
 * @author duyell
 */
@Slf4j
@RestController
@RequestMapping("/ai/conversations")
@RequiredArgsConstructor
public class AgentConversationController {

    private final ConversationService conversationService;

    /** 新建会话（返回会话 id，前端据此开始对话） */
    @PostMapping
    public Result<AiConversation> create(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        String title = body == null || body.get("title") == null ? null : body.get("title").toString();
        AiConversation conversation = conversationService.create(currentUser(request), currentRole(request), title);
        return Result.success(conversation);
    }

    /** 我的会话列表（最近更新在前） */
    @GetMapping
    public Result<List<AiConversation>> list(HttpServletRequest request) {
        return Result.success(conversationService.listForUser(currentUser(request)));
    }

    /**
     * 某会话的历史消息。
     *
     * <p>返回 {@code {conversation, messages}}：前端进入某个历史会话时需要标题，
     * 再多发一次请求只为拿标题没有必要。
     */
    @GetMapping("/{id}/messages")
    public Result<Map<String, Object>> messages(@PathVariable("id") String id,
                                                HttpServletRequest request) {
        String userId = currentUser(request);
        AiConversation conversation = conversationService.requireOwned(id, userId);
        List<AiMessage> messages = conversationService.messages(id, userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversation", conversation);
        data.put("messages", messages);
        return Result.success(data);
    }

    /** 删除会话（连同其消息）。不存在/非本人时返回业务错误，不静默成功。 */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable("id") String id,
                                              HttpServletRequest request) {
        conversationService.delete(id, currentUser(request));
        return Result.success(Map.of("deleted", true, "id", id));
    }

    private String currentUser(HttpServletRequest request) {
        return (String) request.getAttribute("username");
    }

    private String currentRole(HttpServletRequest request) {
        return (String) request.getAttribute("role");
    }
}
