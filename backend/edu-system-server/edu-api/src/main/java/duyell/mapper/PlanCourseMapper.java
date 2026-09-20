package duyell.mapper;

import com.duyell.PlanCourse;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 培养计划课程明细 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface PlanCourseMapper {

    /** 查某方案的全部课程明细（按类别、建议学期排序） */
    @Select("""
            select * from plan_course
            where plan_id = #{planId}
            order by category desc, suggest_semester, course_code
            """)
    List<PlanCourse> listByPlanId(@Param("planId") Integer planId);

    /** 查某方案的指定类别课程明细 */
    @Select("""
            select * from plan_course
            where plan_id = #{planId} and category = #{category}
            order by suggest_semester, course_code
            """)
    List<PlanCourse> listByPlanIdAndCategory(@Param("planId") Integer planId,
                                             @Param("category") String category);

    /** 查某方案中指定课程代码的明细（判断某门课是否属于该方案的必修） */
    @Select("""
            select * from plan_course
            where plan_id = #{planId} and course_code = #{courseCode}
            limit 1
            """)
    PlanCourse selectByPlanAndCode(@Param("planId") Integer planId,
                                   @Param("courseCode") String courseCode);

    /** 新增明细（培养计划维护用） */
    @Insert("""
            insert into plan_course(plan_id, course_code, course_name, category, suggest_semester, credit, remark)
            values(#{planId}, #{courseCode}, #{courseName}, #{category}, #{suggestSemester}, #{credit}, #{remark})
            """)
    void add(PlanCourse planCourse);

    /** 按明细 id 删除 */
    @Delete("delete from plan_course where id = #{id}")
    void deleteById(@Param("id") Integer id);

    /** 按方案 id 删除全部明细（方案删除时级联清理） */
    @Delete("delete from plan_course where plan_id = #{planId}")
    void deleteByPlanId(@Param("planId") Integer planId);
}
