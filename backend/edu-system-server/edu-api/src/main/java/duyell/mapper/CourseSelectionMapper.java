package duyell.mapper;

import com.duyell.CourseSelection;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author duyell
 */
@Mapper
public interface CourseSelectionMapper {
    /**
     * 添加选课信息
     * @param courseId 课程id
     * @param studentId 学生id
     * @param roundId 选课时命中的轮次（可空，历史数据没有）
     */
    @Insert("insert into course_selection(course_id, student_id, round_id) " +
            "values(#{courseId},#{studentId},#{roundId})")
    void add(@Param("courseId") Integer courseId,
             @Param("studentId") String studentId,
             @Param("roundId") Integer roundId);

    /**
     * 该生在某学期已选课程的**学分总和**（选课时的学分上限校验用）。
     *
     * <p>注意学分取 {@code course.credit}（实际要上的那门课），不是培养计划里的快照值。
     */
    @Select("""
            select coalesce(sum(c.credit), 0)
            from course_selection cs
            join course c on cs.course_id = c.id
            where cs.student_id = #{studentId} and c.term = #{term}
            """)
    BigDecimal sumSelectedCredits(@Param("studentId") String studentId, @Param("term") String term);

    /**
     * 删除选课信息
     * @param courseId 课程id
     * @param studentId 学生id
     */
    @Delete("delete from course_selection where course_id = #{courseId} and student_id = #{studentId}")
    void delete(Integer courseId, String studentId);

    /**
     * 删除选课信息
     * @param courseId 课程id
     */
    @Delete("delete from course_selection where course_id = #{courseId}")
    void deleteByCourseId(Integer courseId);

    /**
     * 删除选课信息
     * @param studentId 学生id
     */
    @Delete("delete from course_selection where student_id = #{studentId}")
    void deleteByStudentId(String studentId);

    /**
     * 查询选课信息
     * @param courseId 课程id
     * @return 选课信息列表
     */
    @Select("select * from course_selection where course_id = #{courseId}")
    List<CourseSelection> selectByCourseId(Integer courseId);

    /**
     * 查询选课信息
     * @param studentId 学生id
     * @return 选课信息列表
     */
    @Select("select * from course_selection where student_id = #{studentId}")
    List<CourseSelection> selectByStudentId(String studentId);

    /**
     * 查询单条选课记录
     * @param courseId 课程id
     * @param studentId 学生id
     * @return 选课记录（未选时返回 null）
     */
    @Select("select * from course_selection where course_id = #{courseId} and student_id = #{studentId}")
    CourseSelection select(Integer courseId, String studentId);

    /**
     * 统计某课程的已选人数
     * @param courseId 课程id
     * @return 已选人数
     */
    @Select("select count(*) from course_selection where course_id = #{courseId}")
    int countByCourseId(Integer courseId);

    /**
     * 统计选课数量
     * @return 选课数量
     */
    @Select("select count(*) from course_selection")
    int count();

    /**
     * 分页查询
     * @return 选课信息列表
     */
    @Select("select * from course_selection")
    List<CourseSelection> list();

    /**
     * 批量删除选课信息
     * @param ids 选课信息id的集合
     */
    void deleteByIds(List<Integer> ids);


}
