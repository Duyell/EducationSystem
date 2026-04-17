package duyell.mapper;

import com.duyell.CourseSelection;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
     */
    @Insert("insert into course_selection(course_id, student_id) values(#{courseId},#{studentId})")
    void add(Integer courseId, Integer studentId);

    /**
     * 删除选课信息
     * @param courseId 课程id
     * @param studentId 学生id
     */
    @Delete("delete from course_selection where course_id = #{courseId} and student_id = #{studentId}")
    void delete(Integer courseId, Integer studentId);

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
    void deleteByStudentId(Integer studentId);

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
    List<CourseSelection> selectByStudentId(Integer studentId);

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
