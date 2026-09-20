package duyell.mapper;

import com.duyell.TrainingPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 培养计划 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface TrainingPlanMapper {

    /**
     * 按「专业 + 年级」查询启用中的培养计划 —— 学生适用方案的定位方式。
     *
     * <p>设计文档 §3.2(1)：老生沿用其入学年级对应的版本。
     *
     * @param majorId 专业 id（来自 clazz.major_id）
     * @param grade   年级（来自 clazz.grade）
     * @return 匹配的计划；无则返回 null
     */
    @Select("""
            select tp.*, m.major_name as majorName, col.college_name as collegeName
            from training_plan tp
            left join major m on tp.major_id = m.id
            left join college col on m.college_id = col.id
            where tp.major_id = #{majorId} and tp.grade = #{grade} and tp.status = 1
            limit 1
            """)
    TrainingPlan selectByMajorAndGrade(@Param("majorId") Integer majorId,
                                       @Param("grade") String grade);

    /** 按 id 查询 */
    @Select("""
            select tp.*, m.major_name as majorName, col.college_name as collegeName
            from training_plan tp
            left join major m on tp.major_id = m.id
            left join college col on m.college_id = col.id
            where tp.id = #{id}
            """)
    TrainingPlan selectById(@Param("id") Integer id);

    /** 列表（可按专业/年级筛选，均可空） */
    @Select("""
            <script>
            select tp.*, m.major_name as majorName, col.college_name as collegeName
            from training_plan tp
            left join major m on tp.major_id = m.id
            left join college col on m.college_id = col.id
            <where>
              <if test="majorId != null">and tp.major_id = #{majorId}</if>
              <if test="grade != null and grade != ''">and tp.grade = #{grade}</if>
            </where>
            order by tp.grade desc, tp.major_id
            </script>
            """)
    List<TrainingPlan> list(@Param("majorId") Integer majorId, @Param("grade") String grade);
}
