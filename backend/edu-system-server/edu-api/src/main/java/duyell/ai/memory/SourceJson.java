package duyell.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 来源卡片出处的**序列化边界**（M3 收尾）。
 *
 * <p><b>为什么要单独一个类</b>：出处要在三个地方变形——运行时（事件载荷，已是对象）、
 * 数据库（只能是字符串）、接口响应（又要变回对象）。若把 {@code ObjectMapper} 分别塞进
 * 写入方与读取方，就成了"两处各写一遍 JSON 处理"，两边稍有出入（一边写数组、一边按单对象读）
 * 就会出现"卡片有时有、有时没有"这种极难查的问题。这里收成一处，两侧都只能走它。
 *
 * <p><b>解析失败一律降级为空列表、不抛异常</b>：出处是附加信息，一条脏数据/老数据
 * 解析不出来，不该让整个历史会话接口 500（那等于因为一个卡片让用户看不到聊天记录）。
 *
 * @author duyell
 */
@Slf4j
public final class SourceJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP =
            new TypeReference<>() {
            };

    private SourceJson() {
    }

    /**
     * 出处对象 → 落库字符串。
     *
     * @return {@code null}：无出处（**不是** {@code "[]"}）——让"这条消息没有来源"
     *         在数据库里一眼可辨，也让老数据的语义（NULL）与新数据一致
     */
    public static String write(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("来源序列化失败，将不落库（回答本身不受影响）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 落库字符串 → 出处对象。
     *
     * @return 永不为 {@code null}；无法解析时返回空列表
     */
    public static List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = MAPPER.readValue(json, LIST_OF_MAP);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("来源解析失败，按无来源处理: {}", e.getMessage());
            return List.of();
        }
    }
}
