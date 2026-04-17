package duyell.mapper;

import com.duyell.Major;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author duyell
 */
@Mapper
public interface MajorMapper {
    /**
     * 添加专业
     * @param major 专业对象
     */
    @Insert("insert into major(major_name,college_id) values(#{majorName},#{collegeId})")
    void add(Major major);

    /**
     * 删除专业
     * @param majorId 专业id
     */
    @Delete("delete from major where id = #{majorId}")
    void delete(Integer majorId);

    /**
     * 修改专业信息
     * @param major 专业对象
     */
    void update(Major major);

    /**
     * 查询专业名称
     * @param majorId 专业id
     * @return 专业名称
     */
    @Select("select major_name from major where id = #{majorId}")
    String selectMajorName(Integer majorId);

    /**
     * 分页查询
     * @return 查询列表
     */
    @Select("select * from major")
    List<Major> list();

    /**
     * 统计专业数量
     * @return 专业数量
     */
    @Select("select count(*) from major")
    int countMajor();
}
