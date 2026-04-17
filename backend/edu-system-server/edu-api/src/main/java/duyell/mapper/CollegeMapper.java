package duyell.mapper;

import com.duyell.College;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * @author duyell
 */
@Mapper
public interface CollegeMapper {
    /**
     * 添加学院
     * @param collegeName 学院名称
     */
    @Insert("insert into college(college_name) values(#{collegeName})")
    void addCollege(String collegeName);

    /**
     * 删除学院
     * @param collegeId 学院id
     */
    @Delete("delete from college where id = #{collegeId}")
    void deleteCollege(Integer collegeId);

    /**
     * 修改学院名称
     * @param collegeId 学院id
     * @param collegeName 新的学院名称
     */
    @Update("update college set college_name = #{collegeName} where id = #{collegeId}")
    void updateCollege(Integer collegeId, String collegeName);

    /**
     * 查询学院名称
     * @param collegeId 学院id
     * @return 学院名称
     */
    @Select("select college_name from college where id = #{collegeId}")
    String selectCollegeName(Integer collegeId);

    /**
     * 分页查询
     * @return 查询列表
     */
    @Select("select * from college")
    List<College> list();

    /**
     * 统计学院数量
     * @return 学院数量
     */
    @Select("select count(*) from college")
    int countCollege();

    /**
     * 批量删除学院
     * @param ids 学院id的集合
     */
    void deleteByIds(List<Integer> ids);
}
