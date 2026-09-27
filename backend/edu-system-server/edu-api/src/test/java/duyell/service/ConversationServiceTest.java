package duyell.service;

import com.duyell.AiConversation;
import com.duyell.AiMessage;
import duyell.ai.memory.MybatisChatMemory;
import duyell.ai.memory.SourceJson;
import duyell.mapper.AiMessageMapper;
import duyell.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话与多轮记忆测试（M2 计划 1.5 / 1.6）。
 *
 * <p>要证明的四件事：
 * <ol>
 *   <li><b>归属校验真的挡住了越权</b>：换个用户读/删别人的会话必须失败，且**数据没被动过**
 *       ——只断言"抛异常"是不够的，删了一半也算抛异常；</li>
 *   <li><b>记忆不会重复落库</b>：逐轮追加时，条数应等于轮次的 2 倍。
 *       这正是没用框架 {@code MessageWindowChatMemory} 的原因（它每轮保存整个窗口），
 *       所以这条断言也是那个设计决定的回归防线；</li>
 *   <li><b>窗口只影响模型上下文，不影响历史</b>：超过窗口后，模型只看到最近 N 条，
 *       而库里**一条不少**（界面往上翻还能看到自己的历史）；</li>
 *   <li><b>标题由首条用户消息生成，且只生成一次</b>——列表里不该出现一排"新对话"。</li>
 * </ol>
 *
 * <p>用真实库 + 回滚事务隔离，可反复跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ConversationServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AiMessageMapper messageMapper;

    @Autowired
    private ChatMemory chatMemory;

    private static final String ALICE = "2023001";
    private static final String BOB = "2023002";

    private AiConversation newConversation(String user) {
        return conversationService.create(user, "student", null);
    }

    private void appendTurn(AiConversation c, String userText, String assistantText) {
        conversationService.appendUserMessage(c.getId(), userText);
        conversationService.appendAssistantMessage(c.getId(), assistantText, List.of());
    }

    /**
     * 容器里**只能有一个** ChatMemory，且必须是本项目基于 MySQL 的实现。
     *
     * <p>Spring AI 的自动配置也会提供一个（{@code MessageWindowChatMemory}），
     * 它是 {@code @ConditionalOnMissingBean}——这条断言就是在确认"退让"真的发生了：
     * 如果没退让，本测试类按类型注入就会直接失败，或者悄悄用到内存实现（重启即失忆）。
     */
    @Test
    void containerProvidesExactlyTheMybatisBackedMemory() {
        assertInstanceOf(MybatisChatMemory.class, chatMemory,
                "容器里的 ChatMemory 必须是 MySQL 实现，否则会话重启就失忆");
    }

    @Test
    void createListsOnlyOwnConversations() {
        AiConversation mine = conversationService.create(ALICE, "student", "我的会话");
        AiConversation others = conversationService.create(BOB, "student", "别人的会话");

        // 只断言"我的在里面、别人的不在"，**不断言总数**：同一用户可能有其它测试或手工实跑
        // 留下的会话（例如 OutputGuardrailIntegrationTest 走的是真实落库路径），
        // 断言 size==1 会让这个测试被无关的历史数据搞红。
        List<AiConversation> aliceList = conversationService.listForUser(ALICE);
        assertTrue(aliceList.stream().anyMatch(c -> c.getId().equals(mine.getId())),
                "自己的会话必须出现在列表里");
        assertTrue(aliceList.stream().noneMatch(c -> c.getId().equals(others.getId())),
                "别人的会话不该出现在我的列表里");

        AiConversation listed = aliceList.stream()
                .filter(c -> c.getId().equals(mine.getId())).findFirst().orElseThrow();
        assertEquals("我的会话", listed.getTitle());
        assertEquals("student", listed.getRole(), "角色要落库，便于列表展示与排查");
        assertEquals(0, listed.getMessageCount(), "新会话没有消息");
    }

    /** 逐轮追加：条数精确等于 2×轮数（没有重复写、没有漏写） */
    @Test
    void appendingTurnByTurnDoesNotDuplicateMessages() {
        AiConversation c = newConversation(ALICE);

        appendTurn(c, "我选了什么课？", "你选了三门课。");
        appendTurn(c, "那第二门呢？", "第二门是数据结构。");

        List<AiMessage> rows = messageMapper.listByConversation(c.getId());
        assertEquals(4, rows.size(), "两轮对话就是 4 条；多于 4 条说明窗口被整份重写");
        assertEquals(List.of("user", "assistant", "user", "assistant"),
                rows.stream().map(AiMessage::getRole).toList(), "顺序必须是对话顺序");
        assertEquals("那第二门呢？", rows.get(2).getContent());
        assertEquals(4, conversationService.requireOwned(c.getId(), ALICE).getMessageCount(),
                "会话计数要跟着涨，列表里才看得出聊过多少");
    }

    /**
     * 窗口 semantics：超过上限后模型只看到**最近** N 条，而库里一条不少。
     *
     * <p>用默认窗口 20 条（{@code ai.memory.max-messages}）= 11 轮（22 条）来跨过边界：
     * 应保留第 2..22 条，丢掉最早的 U1/A1。
     */
    @Test
    void modelWindowKeepsMostRecentButDatabaseKeepsEverything() {
        AiConversation c = newConversation(ALICE);
        for (int i = 1; i <= 11; i++) {
            appendTurn(c, "U" + i, "A" + i);
        }

        List<Message> window = chatMemory.get(c.getId());
        assertEquals(20, window.size(), "窗口应按 ai.memory.max-messages 截到 20 条");

        // 窗口内容是"最近的"：最新一条在最后，最早的一条是第二轮的 U2（U1/A1 已被挤出窗口）
        assertEquals("A11", window.get(window.size() - 1).getText());
        assertEquals("U2", window.get(0).getText(), "被挤出的应是最早的两条，而不是最新的");

        // 历史不丢：界面/审计读的是这张表，一条都不少
        assertEquals(22, messageMapper.countByConversation(c.getId()),
                "窗口只裁剪模型上下文，不能真的删掉用户的历史消息");
        assertEquals(22, conversationService.messages(c.getId(), ALICE).size(),
                "历史消息接口应返回全部 22 条");
    }

    /** 助手本轮没有任何正文（只调了工具）时不留空消息 */
    @Test
    void blankAssistantContentIsNotPersisted() {
        AiConversation c = newConversation(ALICE);
        conversationService.appendUserMessage(c.getId(), "帮我看看成绩");
        conversationService.appendAssistantMessage(c.getId(), "   ", List.of());

        List<AiMessage> rows = messageMapper.listByConversation(c.getId());
        assertEquals(1, rows.size(), "空白正文不该落库（否则历史里全是空轮次）");
        assertEquals(MessageType.USER, chatMemory.get(c.getId()).get(0).getMessageType());
    }

    /**
     * **来源随消息落库**（M3 来源卡片持久化）：刷新历史会话时卡片必须还在。
     *
     * <p>这条踩过坑：出处最初只作为 SSE 事件推给前端，落库时已经拿不到了——
     * 于是"刚问完有卡片、刷新就没了"。断言里既查库里的原始 JSON（写入确实发生），
     * 也查接口返回的解析结果（形状对得上），两头都盯住。
     */
    @Test
    void assistantSourcesArePersistedWithTheMessage() {
        AiConversation c = newConversation(ALICE);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("docId", "03");
        source.put("docTitle", "重修与补考办法");
        source.put("section", "3. 补考");
        source.put("citation", "重修与补考办法 3. 补考");

        conversationService.appendUserMessage(c.getId(), "补考通过以后绩点怎么算");
        conversationService.appendAssistantMessage(c.getId(), "按 60 分记载。", List.of(source));

        List<AiMessage> rows = messageMapper.listByConversation(c.getId());
        AiMessage assistant = rows.get(rows.size() - 1);
        assertEquals(AiMessage.ROLE_ASSISTANT, assistant.getRole());
        assertTrue(assistant.getSourcesJson() != null
                        && assistant.getSourcesJson().contains("重修与补考办法 3. 补考"),
                "出处必须落库，否则刷新后卡片消失：" + assistant.getSourcesJson());
        assertEquals(List.of(source), SourceJson.parse(assistant.getSourcesJson()),
                "落库再解析回来应与写入时逐字段一致（字段名漂移会让前端静默不显示）");
    }

    /** 没有出处的消息：库里是 NULL（而不是 "[]"），且解析回来是空列表而不是 null */
    @Test
    void messagesWithoutSourcesStoreNullAndParseToEmptyList() {
        AiConversation c = newConversation(ALICE);
        appendTurn(c, "我绩点多少", "3.88");

        AiMessage assistant = messageMapper.listByConversation(c.getId()).get(1);
        assertNull(assistant.getSourcesJson(),
                "无出处应存 NULL：'没有来源'与'来源为空数组'不必区分，但 NULL 一眼可辨");
        assertTrue(SourceJson.parse(assistant.getSourcesJson()).isEmpty());
        assertTrue(SourceJson.parse("这不是 JSON").isEmpty(), "脏数据不能让历史接口 500");
        assertTrue(SourceJson.parse(null).isEmpty());
        assertNull(SourceJson.write(List.of()), "空列表不该写成 '[]'（保持与老数据一致的 NULL 语义）");
    }

    @Test
    void titleComesFromFirstUserMessageAndIsNotOverwritten() {
        AiConversation c = newConversation(ALICE);
        assertEquals(ConversationServiceImpl.DEFAULT_TITLE, c.getTitle());

        conversationService.appendUserMessage(c.getId(), "我这学期有几门课？");
        assertEquals("我这学期有几门课？", conversationService.requireOwned(c.getId(), ALICE).getTitle(),
                "首条用户消息应成为标题，而不是永远叫‘新对话’");

        conversationService.appendUserMessage(c.getId(), "换一个问题：我绩点多少");
        assertEquals("我这学期有几门课？", conversationService.requireOwned(c.getId(), ALICE).getTitle(),
                "标题只生成一次，后续消息不该改写它");
    }

    /** 过长/多行的首条消息：压成一行并按 code point 截断（不能把 emoji 切成半个代理对） */
    @Test
    void longFirstMessageIsSummarizedIntoTidyTitle() {
        AiConversation c = newConversation(ALICE);
        conversationService.appendUserMessage(c.getId(),
                "第一行\n第二行 后面还有很长很长很长很长很长很长的内容用来超过二十个字符的限制😀");

        String title = conversationService.requireOwned(c.getId(), ALICE).getTitle();
        assertFalse(title.contains("\n"), "换行会破坏列表布局，应压成空格：" + title);
        assertTrue(title.codePointCount(0, title.length()) <= 20, "标题不应超过 20 个字符：" + title);
        assertTrue(title.endsWith("…"), "被截断时应有省略号：" + title);
    }

    /** 显式标题不被首条消息覆盖（前端可以按自己的业务命名，例如"选课咨询"） */
    @Test
    void explicitTitleIsKept() {
        AiConversation c = conversationService.create(ALICE, "student", "补退选咨询");
        conversationService.appendUserMessage(c.getId(), "现在还能退课吗？");
        assertEquals("补退选咨询", conversationService.requireOwned(c.getId(), ALICE).getTitle());
    }

    /**
     * 越权读取：换个人来读，必须失败——**并且拿不到任何消息内容**。
     *
     * <p>提示语故意不区分"不存在"与"不是你的"：区分开等于告诉探测者"这个 id 是存在的"。
     */
    @Test
    void otherUserCannotReadConversation() {
        AiConversation c = newConversation(ALICE);
        appendTurn(c, "我的隐私问题", "我的隐私回答");

        BusinessException e = assertThrows(BusinessException.class,
                () -> conversationService.messages(c.getId(), BOB), "别人的会话不该能读");
        assertEquals("会话不存在或无权访问", e.getMessage());
    }

    /** 越权删除：必须失败，且原数据完好（不是"删一半抛异常"） */
    @Test
    void otherUserCannotDeleteConversation() {
        AiConversation c = newConversation(ALICE);
        appendTurn(c, "我选了什么课？", "你选了三门课。");

        assertThrows(BusinessException.class, () -> conversationService.delete(c.getId(), BOB),
                "别人的会话不该能删");

        assertNotNull(conversationService.requireOwned(c.getId(), ALICE), "会话必须还在");
        assertEquals(2, messageMapper.countByConversation(c.getId()), "消息也必须还在");
    }

    /** 不存在/空 id 同样按"无权访问"处理，不给探测者任何区分信号 */
    @Test
    void unknownOrBlankConversationIdIsRejected() {
        assertEquals("会话不存在或无权访问",
                assertThrows(BusinessException.class,
                        () -> conversationService.requireOwned("not-a-real-id", ALICE)).getMessage());
        assertEquals("会话 id 不能为空",
                assertThrows(BusinessException.class,
                        () -> conversationService.requireOwned("   ", ALICE)).getMessage());
        assertEquals("会话归属用户不能为空",
                assertThrows(BusinessException.class,
                        () -> conversationService.listForUser(null)).getMessage());
    }

    /** 本人删除：会话与消息一起清掉，记忆窗口也一起失效 */
    @Test
    void ownerCanDeleteConversationWithItsMessages() {
        AiConversation c = newConversation(ALICE);
        appendTurn(c, "我选了什么课？", "你选了三门课。");

        assertTrue(conversationService.delete(c.getId(), ALICE));

        assertTrue(messageMapper.listByConversation(c.getId()).isEmpty(), "消息应随会话一起删除");
        assertEquals(0, messageMapper.countByConversation(c.getId()));
        assertTrue(chatMemory.get(c.getId()).isEmpty(), "记忆窗口不应残留已删会话的消息");
        assertTrue(conversationService.listForUser(ALICE).stream()
                        .noneMatch(item -> item.getId().equals(c.getId())),
                "列表里不应再出现已删除的会话");
    }

    /** 会话 id 是 UUID：不可枚举（自增 id 会被顺序猜测，越权探测成本极低） */
    @Test
    void conversationIdIsUuid() {
        String id = newConversation(ALICE).getId();
        assertEquals(36, id.length(), "UUID 字符串长度应为 36：" + id);
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "会话 id 应是 UUID，实际 " + id);
    }
}
