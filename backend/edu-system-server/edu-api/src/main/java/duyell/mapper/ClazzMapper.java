package duyell.mapper;

import com.duyell.Clazz;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * @author duyell
 */
@Mapper
public interface ClazzMapper {
    /**
     * 添加班级
     * @param clazz 班级对象
     */
    @Insert("insert into clazz(clazz_name, major_id, grade) " +
            "VALUES (#{clazzName},#{majorId},#{grade})")
    void add(Clazz clazz);

    /**
     * 删除班级
     * @param id 班级id
     */
    @Delete("delete from clazz where id = #{id}")
    void delete(Integer id);

    /**
     * 修改班级信息
     * @param clazz 班级对象
     */
    void update(Clazz clazz);


    /**
     * 查询班级名称
     * @param id 班级id
     * @return 班级名称
     */
    @Select("select clazz_name from clazz where id = #{id}")
    String selectClazzName(Integer id);

    /**
     * 分页查询
     * @param clazzName 班级名称
     * @param grade 年级
     * @param majorId 专业id
     * @param collegeId 学院id
     * @return 查询列表
     */
    List<Clazz> list(String clazzName, String grade, Integer majorId, Integer collegeId);


    /**
     * 统计班级数量
     * @return 班级数量
     */
    @Select("select count(*) from clazz")
    int countClazz();

    /**
     * 批量删除班级
     * @param ids 班级id的集合
     */
    void deleteByIds(List<Integer> ids);
}