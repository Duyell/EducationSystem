package duyell.mapper;

import com.duyell.SelectionRound;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 选课轮次 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface SelectionRoundMapper {

    @Select("select * from selection_round where id = #{id}")
    SelectionRound selectById(@Param("id") Integer id);

    /** 列表（term / status 均可空） */
    List<SelectionRound> list(@Param("term") String term, @Param("status") Integer status);

    /**
     * 某学期**已开启**的轮次。
     *
     * <p>只按 term + status 过滤，**适用范围在 Service 里判**——
     * "学生是否落在范围内"需要年级/专业/学院，SQL 里再 join 一层会让规则散落两处。
     */
    @Select("select * from selection_round where term = #{term} and status = 1 order by id")
    List<SelectionRound> selectOpenByTerm(@Param("term") String term);

    /** 全部已开启的轮次（跨学期），用于"当前有没有任何选课在开放" */
    @Select("select * from selection_round where status = 1 order by term, id")
    List<SelectionRound> selectAllOpen();

    @Insert("""
            insert into selection_round(round_name, term, select_start, select_end,
                                        drop_start, drop_end, status, max_credits)
            values(#{roundName}, #{term}, #{selectStart}, #{selectEnd},
                   #{dropStart}, #{dropEnd}, #{status}, #{maxCredits})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(SelectionRound round);

    /** 更新（动态 SQL 见 XML；不含 create_time） */
    void update(SelectionRound round);

    @Update("update selection_round set status = #{status} where id = #{id}")
    void updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    @Delete("delete from selection_round where id = #{id}")
    void deleteById(@Param("id") Integer id);

    /** 同一学期是否已有轮次（防重复建，且便于给出友好提示） */
    @Select("select count(*) from selection_round where term = #{term} and round_name = #{roundName}")
    int countByTermAndName(@Param("term") String term, @Param("roundName") String roundName);
}
