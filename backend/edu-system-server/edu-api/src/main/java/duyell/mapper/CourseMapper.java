package duyell.mapper;

import com.duyell.Course;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CourseMapper {

    /**
     * 添加课程
     * @param course 课程对象
     */
    @Insert("insert into course(course_name,teacher_id,college_id,term,credit,class_hour,max_student) " +
            "values (#{courseName},#{teacherId},#{collegeId},#{term},#{credit},#{classHour},#{maxStudent} )")
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
     * 查询课程名称
     * @param id 课程id
     * @return 课程名称
     */
    @Select("select * from course where id = #{id}")
    String selectCourseById(Integer id);

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
