package duyell.ai.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出护栏 {@link ToolCallTextGuard} 的单元测试（纯逻辑，不依赖 Spring / 模型）。
 *
 * <p>覆盖重点是**流式边界**与**不误伤**：
 * <ul>
 *   <li>模型把工具调用写进正文时，能完整恢复出 name / arguments；</li>
 *   <li>标记被切成多个增量（真实流式一定会发生）时，不能提前把半截 JSON 漏给用户；</li>
 *   <li>该展示的正文必须照常展示——护栏宁可漏认，也不能吞掉用户的回答；</li>
 *   <li>解析不出来的东西一律当普通文本放行。</li>
 * </ul>
 *
 * <p>第一条用例的输入就是本项目**实测抓到**的原始输出（见 `docs/开发记录.md` (十) 末节与 (十五)）。
 */
class ToolCallTextGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<String> chars(String s) {
        return s.chars().mapToObj(c -> String.valueOf((char) c)).toList();
    }

    @Test
    void recoversTheExactPayloadObservedFromQwen() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        // 实测原文：前面一段噪声 + 裸 JSON + 闭合标签
        String raw = "bral\n{\"name\": \"approve_course_apply\", \"arguments\": {\"applyId\": 1}}\n</tool_call>";

        StringBuilder visible = new StringBuilder();
        for (String chunk : chars(raw)) {
            visible.append(guard.feed(chunk));
        }
        ToolCallTextGuard.Result result = guard.finish();

        assertEquals(1, result.calls().size(), "应恢复出 1 个调用");
        assertEquals("approve_course_apply", result.calls().get(0).name());
        assertEquals("{\"applyId\":1}", result.calls().get(0).arguments());
        assertTrue(visible.toString().indexOf("applyId") < 0, "原始 JSON 绝不能流给用户： " + visible);
        assertTrue(result.trailingText().isEmpty(), "恢复出调用时不应再补发那段噪声文本");
    }

    @Test
    void recoversTaggedFormAndKeepsSurroundingProse() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        String raw = "好的，我先查一下。\n<tool_call>\n{\"name\": \"get_my_gpa\", \"arguments\": {}}\n</tool_call>\n请稍等。";

        String visible = guard.feed(raw) + guard.finish().trailingText();
        ToolCallTextGuard.Result result = guard.finish();

        assertEquals(1, result.calls().size());
        assertEquals("get_my_gpa", result.calls().get(0).name());
        assertEquals("{}", result.calls().get(0).arguments(), "arguments 为空对象时应规范化为 {}");
        assertTrue(visible.contains("好的，我先查一下。"), "标签前的正文必须保留：" + visible);
        assertTrue(visible.indexOf("get_my_gpa") < 0, "工具名不应出现在正文里：" + visible);
        assertTrue(visible.contains("请稍等。"), "标签后的正文必须保留：" + visible);
    }

    /** 标记被拆成多段增量时，不能提前泄漏半截内容 */
    @Test
    void doesNotLeakWhenMarkerIsSplitAcrossChunks() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        StringBuilder visible = new StringBuilder();

        visible.append(guard.feed("正在处理"));
        visible.append(guard.feed("{\"na"));
        visible.append(guard.feed("me\": \"get_my_exams\", \"argu"));
        visible.append(guard.feed("ments\": {}}"));
        ToolCallTextGuard.Result result = guard.finish();

        assertEquals(1, result.calls().size());
        assertEquals("get_my_exams", result.calls().get(0).name());
        assertEquals("正在处理", visible.toString(), "扣住的部分不能提前出现，正文要原样保留");
    }

    /** 半个 <tool_call> 标签也要扣住，直到确定它不是标签 */
    @Test
    void holdsBackPartialTag() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        StringBuilder visible = new StringBuilder();

        visible.append(guard.feed("开始"));
        visible.append(guard.feed("<tool_ca"));
        assertTrue(visible.toString().indexOf("tool_ca") < 0, "半个标签不能展示：" + visible);

        visible.append(guard.feed("ll> {\"name\": \"list_courses\", \"arguments\": {}} </tool_call>"));
        ToolCallTextGuard.Result result = guard.finish();

        assertEquals(1, result.calls().size());
        assertEquals("list_courses", result.calls().get(0).name());
    }

    /** 正常回答：一字不改地流出去（含普通 JSON，只要不是工具调用的形状） */
    @Test
    void passesNormalTextThroughUntouched() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        String answer = "你的平均学分绩点是 3.8834，专业内排名第 1。{\"foo\": 1} 就这样。";

        String visible = guard.feed(answer);
        ToolCallTextGuard.Result result = guard.finish();

        assertTrue(result.calls().isEmpty(), "不该把普通 JSON 当成工具调用");
        assertEquals(answer, visible + result.trailingText(), "正文必须原样输出");
        assertEquals(answer, result.cleanText());
    }

    /** 名字里没有 name 或解析失败 → 一律当文本，不能吞 */
    @Test
    void malformedOrUnrelatedJsonIsShownAsText() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        String raw = "参考格式：{\"name\" broken} 以及 {\"title\": \"x\"}";

        String visible = guard.feed(raw) + guard.finish().trailingText();
        ToolCallTextGuard.Result result = guard.finish();

        assertTrue(result.calls().isEmpty(), "解析不出来就不该恢复");
        assertEquals(raw, visible, "无法解析的内容要原样展示");
    }

    /** arguments 是字符串形式时也要正确取出（部分模型会把参数再包一层字符串） */
    @Test
    void acceptsArgumentsGivenAsAString() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        String raw = "{\"name\": \"check_time_conflict\", \"arguments\": \"{\\\"courseCode\\\":\\\"CS103\\\"}\"}";

        guard.feed(raw);
        ToolCallTextGuard.Result result = guard.finish();

        assertEquals(1, result.calls().size());
        assertEquals("check_time_conflict", result.calls().get(0).name());
        assertEquals("{\"courseCode\":\"CS103\"}", result.calls().get(0).arguments());
    }

    /** 多个调用（同一段正文里连着写两个） */
    @Test
    void recoversSeveralCalls() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        String raw = "{\"name\": \"get_my_gpa\", \"arguments\": {}} {\"name\": \"get_my_exams\", \"arguments\": {}}";

        guard.feed(raw);
        ToolCallTextGuard.Result result = guard.finish();

        assertEquals(2, result.calls().size());
        assertEquals("get_my_gpa", result.calls().get(0).name());
        assertEquals("get_my_exams", result.calls().get(1).name());
    }

    /** 缓冲兜底：超大文本不该被无限期扣住（这里只验证它最终会被放出来） */
    @Test
    void oversizedBufferIsFlushedAsText() {
        ToolCallTextGuard guard = new ToolCallTextGuard(mapper);
        String big = "x".repeat(9000);

        String visible = guard.feed(big) + guard.finish().trailingText();
        assertTrue(visible.length() >= big.length(), "超大缓冲必须放行");
        assertTrue(guard.finish().calls().isEmpty());
    }
}
