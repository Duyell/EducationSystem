package duyell.mapper;

import com.duyell.SelectionRoundScope;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 选课轮次适用范围 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface SelectionRoundScopeMapper {

    /** 某轮次的全部范围（含专业/学院名，联表见 XML） */
    List<SelectionRoundScope> listByRound(@Param("roundId") Integer roundId);

    /** 一次取多个轮次的范围，避免列表页 N+1 */
    List<SelectionRoundScope> listByRounds(@Param("roundIds") List<Integer> roundIds);

    @Insert("""
            insert into selection_round_scope(round_id, grade, major_id, college_id)
            values(#{roundId}, #{grade}, #{majorId}, #{collegeId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(SelectionRoundScope scope);

    @Delete("delete from selection_round_scope where id = #{id}")
    void deleteById(@Param("id") Integer id);

    @Delete("delete from selection_round_scope where round_id = #{roundId}")
    void deleteByRound(@Param("roundId") Integer roundId);
}
