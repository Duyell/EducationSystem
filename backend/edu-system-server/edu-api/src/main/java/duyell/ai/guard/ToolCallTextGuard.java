package duyell.ai.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式输出护栏：把"模型把工具调用写成了正文"这种情况**接住**。
 *
 * <p><b>要解决的问题</b>：小模型（本项目用 Ollama {@code qwen2.5:7b} 实测）偶尔不走工具调用通道，
 * 而是把调用**当成回答文本**吐出来，例如：
 * <pre>
 * bral
 * {"name": "approve_course_apply", "arguments": {"applyId": 1}}
 * &lt;/tool_call&gt;
 * </pre>
 * 后果有两层：① 用户屏幕上出现一段原始 JSON；② **操作根本没发生**（后端没收到工具调用），
 * 比"执行失败"更隐蔽——用户以为提交了。
 *
 * <p><b>本类做什么</b>：在流式过程中识别这种文本并**扣住不展示**，把其中的工具名与参数解析出来，
 * 交给上层按**正常工具调用**继续走（角色白名单 → 参数 Schema 校验 → 危险操作确认卡片 → 审计）。
 * 因此护栏**不是**安全边界的替代品：它只负责"认出并改道"，一切校验照旧。
 *
 * <p><b>边界与取舍</b>：
 * <ul>
 *   <li>只认"能解析成 {@code {"name": ..., "arguments": ...}} 的 JSON 对象"；
 *       解析不出来就**当普通文本发出去**——宁可多显示一段文字，也不能把用户的回答吞掉。</li>
 *   <li>用户输入里也可能出现同形状的 JSON（提示注入/恰好贴了个例子）。护栏只看**模型输出**；
 *       即便模型把它复述出来，恢复出的调用仍要过白名单与确认卡片，能做的动作与模型主动调用**完全一样**，不新增权限。</li>
 *   <li>缓冲上限 {@value #MAX_BUFFER}：超了就把已缓内容当文本发出去，避免为了识别而长时间不给用户输出。</li>
 *   <li>每次流最多恢复 {@value #MAX_CALLS} 个调用；单个 JSON 最长 {@value #MAX_JSON} 字符。</li>
 * </ul>
 *
 * <p>用法：{@code feed(chunk)} 拿"可以安全展示的文本"，流结束时调 {@code finish()} 取恢复结果。
 * 本类是纯逻辑、无 Spring 依赖，便于单测（见 {@code ToolCallTextGuardTest}）。
 *
 * @author duyell
 */
public final class ToolCallTextGuard {

    /** 从正文里恢复出来的一个工具调用（参数是 JSON 字符串，与模型原生工具调用的格式一致） */
    public record RecoveredCall(String name, String arguments) {
    }

    /**
     * 流结束时的结果。
     *
     * @param calls        恢复出的工具调用（可能为空）
     * @param trailingText 尚未展示的**普通文本**（仅在 {@code calls} 为空时才有意义）
     * @param cleanText    去掉工具调用块之后的完整正文（用于写回模型历史，避免它再看到自己的原始 JSON）
     */
    public record Result(List<RecoveredCall> calls, String trailingText, String cleanText) {
    }

    private static final String TAG_OPEN = "<tool_call>";
    private static final String TAG_CLOSE = "</tool_call>";
    private static final String NAME_LITERAL = "\"name\"";

    private static final int MAX_BUFFER = 8000;
    private static final int MAX_CALLS = 5;
    private static final int MAX_JSON = 4000;

    private final ObjectMapper objectMapper;

    /** 尚未决定去留的尾巴 */
    private final StringBuilder pending = new StringBuilder();
    /** 已经安全发出去的正文 */
    private final StringBuilder emitted = new StringBuilder();
    private final List<RecoveredCall> calls = new ArrayList<>();

    public ToolCallTextGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 喂入一段流式增量。
     *
     * @return 现在就可以展示给用户的文本（可能为空字符串）
     */
    public synchronized String feed(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            pending.append(chunk);
        }
        // 兜底：缓冲过大说明这东西不像工具调用（更像正文），直接放行，别把回答卡住
        if (pending.length() > MAX_BUFFER) {
            String all = pending.toString();
            pending.setLength(0);
            emitted.append(all);
            return all;
        }
        return drain();
    }

    /** 流结束：把剩下的部分处理干净 */
    public synchronized Result finish() {
        String flushed = drain();
        // 收尾这段也要清掉落单的协议标签（它同样会被送往用户界面）
        String trailing = stripStrayTags(pending.toString());
        pending.setLength(0);

        String cleanText;
        if (calls.isEmpty()) {
            // 没有恢复出调用 → 缓住的内容就是普通文本，交由上层展示
            emitted.append(trailing);
            cleanText = emitted.toString();
        } else {
            // 有调用 → 这段文本（通常是 "bral"/"imonial" 之类的噪声）不再展示；历史里也不放原始 JSON
            cleanText = emitted.toString();
            trailing = "";
        }
        return new Result(List.copyOf(calls), trailing, cleanText);
    }

    /** 已恢复出的调用数量（供上层日志用） */
    public synchronized int recoveredCount() {
        return calls.size();
    }

    // ==================================================================
    // 内部：逐块判断"能发出多少、要扣住多少"
    // ==================================================================

    private String drain() {
        StringBuilder out = new StringBuilder();
        boolean progress = true;
        while (progress) {
            progress = false;

            // ① 完整的 <tool_call>...</tool_call>
            int open = pending.indexOf(TAG_OPEN);
            int close = pending.indexOf(TAG_CLOSE);
            if (open >= 0 && close > open) {
                out.append(stripStrayTags(pending.substring(0, open)));
                String block = pending.substring(open + TAG_OPEN.length(), close);
                pending.delete(0, close + TAG_CLOSE.length());
                if (!recover(block)) {
                    out.append(block);   // 不是工具调用 → 原样展示
                }
                progress = true;
                continue;
            }

            // ② 裸 JSON（没有标签）
            JsonStart start = findJsonStart(pending);
            if (start != null) {
                if (start.confirmed()) {
                    int end = findJsonEnd(pending, start.index());
                    if (end >= 0) {
                        out.append(stripStrayTags(pending.substring(0, start.index())));
                        String json = pending.substring(start.index(), end + 1);
                        pending.delete(0, end + 1);
                        if (!recover(json)) {
                            out.append(json);
                        }
                        progress = true;
                        continue;
                    }
                }
                // 起点之前的内容可以安全发出，其余等更多增量
                int holdFrom = start.index();
                int tagPrefix = partialTagIndex(pending);
                if (tagPrefix >= 0 && tagPrefix < holdFrom) {
                    holdFrom = tagPrefix;
                }
                out.append(stripStrayTags(pending.substring(0, holdFrom)));
                pending.delete(0, holdFrom);
                break;
            }

            // ③ 没有 JSON 起点：只需扣住"可能是半个 <tool_call> 标签"的尾巴
            int hold = partialTagIndex(pending);
            if (hold >= 0) {
                out.append(stripStrayTags(pending.substring(0, hold)));
                pending.delete(0, hold);
            } else {
                out.append(stripStrayTags(pending.toString()));
                pending.setLength(0);
            }
            break;
        }
        emitted.append(out);
        return out.toString();
    }

    /** JSON 对象的可能起点 */
    private record JsonStart(int index, boolean confirmed) {
    }

    /**
     * 去掉**孤立的** {@code <tool_call>} / {@code </tool_call>} 标签。
     *
     * <p>为什么要单独处理：实测（2026-09-22 迁移前跑评测基线）出现过模型只吐了一个
     * **没有配对的 {@code </tool_call>}**、外加几个字符残渣的情况，例如：
     * <pre>
     * imonial
     * &lt;/tool_call&gt;您的当前平均学分绩点是 3.8834…
     * </pre>
     * 完整块（有开有闭）由 ① 分支处理；这种**落单的标签**属于模型协议残渣，
     * 展示给学生没有任何意义，所以从要发出去的文本里直接删掉。
     * （评测里那条"回答正文不允许出现工具调用 JSON"的链路不变量就是被它咬红的。）
     */
    private static String stripStrayTags(String text) {
        if (text == null || text.isEmpty() || text.indexOf('<') < 0) {
            return text == null ? "" : text;
        }
        return text.replace(TAG_OPEN, "").replace(TAG_CLOSE, "");
    }

    /**
     * 找到"可能是工具调用 JSON"的起点。
     *
     * <p>{@code confirmed=true} 表示后面已经是完整的 {@code "name"}（可以开始找配对的 {@code }}）；
     * {@code confirmed=false} 表示目前只看到 {@code {"na} 这类**前缀**，需要继续等增量。
     */
    private static JsonStart findJsonStart(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '{') {
                continue;
            }
            int j = i + 1;
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                j++;
            }
            String rest = s.subSequence(j, s.length()).toString();
            if (rest.startsWith(NAME_LITERAL)) {
                return new JsonStart(i, true);
            }
            // rest 是 "name" 的前缀 → 还不能确定，先扣住。
            // ⚠️ 这里**必须**包含 rest 为空的情况（增量恰好停在 `{` 上）：
            // 逐字符喂入时若把孤立的 `{` 当普通文本放出去，后面就再也认不出工具调用了
            // ——本类的单测第一条用例（按字符喂真实抓包内容）就是因为这个漏认而红过。
            if (rest.length() < NAME_LITERAL.length() && NAME_LITERAL.startsWith(rest)) {
                return new JsonStart(i, false);
            }
        }
        return null;
    }

    /** 从 {@code start}（一个 {@code {}）开始找配对的右花括号；找不到返回 -1（字符串与转义会被跳过） */
    private static int findJsonEnd(CharSequence s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 返回"可能是半个协议标签"的起始下标；没有则 -1。
     *
     * <p>⚠️ 必须同时考虑 {@code <tool_call>} **和** {@code </tool_call>}
     * （只有开标签一个字符差：{@code <} vs {@code </}）。
     * 这里曾经只匹配开标签前缀，导致逐字符流式下的 {@code </tool_call>} 被**一个片段一个片段地**
     * 当普通文本放出去：{@code </}、{@code t}、{@code o}……各自都不含完整标签，
     * {@link #stripStrayTags} 自然无从删除，最后用户界面与会话记忆里就留下一串
     * {@code </tool_call>}（2026-09-22 由"落库正文不得含工具调用残渣"的断言抓出来）。
     * 整块喂入时看不出这个问题——所以单测必须有一条**逐字符**喂闭合标签的用例。
     */
    private static int partialTagIndex(CharSequence s) {
        int longest = Math.max(TAG_OPEN.length(), TAG_CLOSE.length());
        int max = Math.min(longest - 1, s.length());
        for (int len = max; len >= 1; len--) {
            int from = s.length() - len;
            String tail = s.subSequence(from, s.length()).toString();
            if (TAG_OPEN.startsWith(tail) || TAG_CLOSE.startsWith(tail)) {
                return from;
            }
        }
        return -1;
    }

    /** 尝试把一个候选片段解析成工具调用；成功返回 true */
    private boolean recover(String candidate) {
        if (calls.size() >= MAX_CALLS) {
            return false;
        }
        String text = candidate == null ? "" : candidate.trim();
        if (text.isEmpty() || text.length() > MAX_JSON || !text.startsWith("{")) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            JsonNode name = node == null ? null : node.get("name");
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                return false;
            }
            JsonNode args = node.get("arguments");
            String arguments;
            if (args == null || args.isNull()) {
                arguments = "{}";
            } else if (args.isTextual()) {
                arguments = args.asText().isBlank() ? "{}" : args.asText();
            } else {
                arguments = objectMapper.writeValueAsString(args);
            }
            calls.add(new RecoveredCall(name.asText(), arguments));
            return true;
        } catch (Exception e) {
            // 解析失败就当普通文本——护栏宁可漏认，也不能吞掉用户的回答
            return false;
        }
    }
}
