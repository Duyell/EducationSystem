package duyell.mapper;

import com.duyell.AiMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 会话消息（{@code ai_message}）。
 *
 * @author duyell
 */
@Mapper
public interface AiMessageMapper {

    @Insert("insert into ai_message(conversation_id, role, content) "
            + "values(#{conversationId}, #{role}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(AiMessage message);

    /** 某会话的全部消息，按写入顺序（即对话顺序） */
    @Select("select * from ai_message where conversation_id = #{conversationId} order by id")
    List<AiMessage> listByConversation(@Param("conversationId") String conversationId);

    /** 最近 N 条（用于组窗口：先按 id 倒序取 N 条，再在服务层反转回正序） */
    @Select("select * from ai_message where conversation_id = #{conversationId} "
            + "order by id desc limit #{limit}")
    List<AiMessage> listRecent(@Param("conversationId") String conversationId,
                               @Param("limit") int limit);

    @Delete("delete from ai_message where conversation_id = #{conversationId}")
    void deleteByConversation(@Param("conversationId") String conversationId);

    @Select("select count(*) from ai_message where conversation_id = #{conversationId}")
    int countByConversation(@Param("conversationId") String conversationId);

    /**
     * 所有有消息的会话 id（Spring AI 的 {@code ChatMemoryRepository} 需要它）。
     *
     * <p>本项目实际只用"按会话取消息"，这个方法是为满足框架接口而实现；
     * 带上 limit 的意图是：真要有人调用它，也不至于把整表拉进内存。
     */
    @Select("select distinct conversation_id from ai_message order by conversation_id limit 1000")
    List<String> listConversationIds();
}
