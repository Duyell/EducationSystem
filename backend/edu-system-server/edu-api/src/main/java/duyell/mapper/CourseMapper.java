package duyell.mapper;

import com.duyell.Course;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CourseMapper {

    /**
     * 添加课程。
     *
     * <p>回填自增 id：审批通过开课申请后需要用新课程 id 建立关联（{@code course_apply.created_course_id}）。
     *
     * @param course 课程对象
     */
    @Insert("insert into course(course_code,course_name,teacher_id,college_id,term,credit,class_hour,max_student) " +
            "values (#{courseCode},#{courseName},#{teacherId},#{collegeId},#{term},#{credit},#{classHour},#{maxStudent} )")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(Course course);

    /**
     * 删除课程
     * @param courseId 课程id
     */
    @Delete("delete from course where id = #{courseId}")
    void delete(Integer courseId);

    /**
     * 修改课程信息
     * @param course 课程对象
     */
    void update(Course course);

    /**
     * 查询课程
     * @param id 课程id
     * @return 课程
     */
    Course selectCourseById(Integer id);

    /**
     * 查询课程并加行锁（选课时串行化同一课程的并发请求，防止超卖）
     * @param id 课程id
     * @return 课程
     */
    @Select("select * from course where id = #{id} for update")
    Course selectCourseByIdForUpdate(Integer id);

    /**
     * 根据ID集合批量查询课程（含教师/学院名，避免 N+1）
     * @param ids 课程id集合
     * @return 课程列表
     */
    List<Course> selectByIds(List<Integer> ids);

    /**
     * 根据教师ID查询课程
     * @param teacherId 教师工号
     * @return 课程列表
     */
    List<Course> selectByTeacherId(String teacherId);

    /**
     * 按课程代码查 id（同一代码可能有多条开课记录，取最早的一条）。
     *
     * <p>课程代码是培养计划/已修判定/补考关联的唯一依据，P2 的排课与 P3 的选课都要用它反查。
     *
     * @param courseCode 课程代码，如 CS101
     * @return 课程 id；无则 null
     */
    @Select("select id from course where course_code = #{courseCode} order by id limit 1")
    Integer selectIdByCode(@Param("courseCode") String courseCode);

    /**
     * 查询课程
     * @param courseName 课程名称
     * @return 课程
     */
    @Select("select * from course where course_name = #{courseName}")
    List<Course> selectCourseByName(String courseName);

    /**
     * 分页查询
     * @param courseName 课程名称
     * @param teacherName 教师名称
     * @param teacherId 教师id
     * @param collegeId 学院id
     * @param credit 学分
     * @param classHour 课时
     * @param maxStudent 最大人数
     * @return 课程列表
     */
    List<Course> list(@Param("courseName") String courseName,@Param("teacherName") String teacherName, @Param("teacherId") String teacherId, @Param("collegeId") Integer collegeId, @Param("credit") Integer credit, @Param("classHour") Integer classHour, @Param("maxStudent") Integer maxStudent);

    /**
     * 统计课程数量
     * @return 课程数量
     */
    @Select("select count(*) from course")
    int countCourse();

    /**
     * 批量删除课程
     * @param ids 课程id的集合
     */
    void deleteByIds(List<Integer> ids);
}
