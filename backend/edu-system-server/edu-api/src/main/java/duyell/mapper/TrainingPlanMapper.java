package duyell.mapper;

import com.duyell.TrainingPlan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /** 新增方案，回填自增 id */
    @Insert("""
            insert into training_plan(plan_name, major_id, grade, total_credits,
                                      required_credits, elective_credits, status, remark)
            values(#{planName}, #{majorId}, #{grade}, #{totalCredits},
                   #{requiredCredits}, #{electiveCredits}, #{status}, #{remark})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(TrainingPlan plan);

    /** 更新方案（不含 create_time；update_time 由数据库自动维护） */
    @Update("""
            update training_plan
            set plan_name = #{planName}, major_id = #{majorId}, grade = #{grade},
                total_credits = #{totalCredits}, required_credits = #{requiredCredits},
                elective_credits = #{electiveCredits}, status = #{status}, remark = #{remark}
            where id = #{id}
            """)
    void update(TrainingPlan plan);

    /** 查同专业同年级是否已有方案（用于新建前查重，排除自身） */
    @Select("""
            select * from training_plan
            where major_id = #{majorId} and grade = #{grade} and id <> #{excludeId}
            limit 1
            """)
    TrainingPlan selectDuplicate(@Param("majorId") Integer majorId,
                                 @Param("grade") String grade,
                                 @Param("excludeId") Integer excludeId);
}
