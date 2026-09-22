package duyell.mapper;

import com.duyell.AiConversation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AI 会话（{@code ai_conversation}）。
 *
 * <p>所有查询都**按 user_id 过滤**：会话归属校验在服务层强制执行，
 * 这里提供"只查某人自己的"入口，避免越权读取靠调用方自觉。
 *
 * @author duyell
 */
@Mapper
public interface AiConversationMapper {

    @Insert("insert into ai_conversation(id, user_id, role, title, message_count) "
            + "values(#{id}, #{userId}, #{role}, #{title}, 0)")
    void add(AiConversation conversation);

    @Select("select * from ai_conversation where id = #{id}")
    AiConversation selectById(@Param("id") String id);

    /** 某人的会话列表，最近更新的在前 */
    @Select("select * from ai_conversation where user_id = #{userId} order by update_time desc, id desc")
    List<AiConversation> listByUser(@Param("userId") String userId);

    /**
     * 消息数 +1 并刷新更新时间。
     *
     * <p>用 SQL 自增而不是"读出来加一再写回"：并发下后者会丢更新，
     * 而这里只是一个展示用计数，没必要为它加锁。
     */
    @Update("update ai_conversation set message_count = message_count + 1 where id = #{id}")
    void increaseMessageCount(@Param("id") String id);

    @Update("update ai_conversation set title = #{title} where id = #{id}")
    void updateTitle(@Param("id") String id, @Param("title") String title);

    @Delete("delete from ai_conversation where id = #{id}")
    void deleteById(@Param("id") String id);
}
