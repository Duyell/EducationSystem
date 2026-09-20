package duyell.mapper;

import com.duyell.Score;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * @author duyell
 */
@Mapper
public interface ScoreMapper {
    /**
     * 添加成绩
     * @param score 成绩
     */
    @Insert("insert into score(course_id, student_id, usual_score, exam_score, total_score, makeup_score, passed) "
            + "values(#{courseId},#{studentId},#{usualScore},#{examScore},#{totalScore},#{makeupScore},#{passed})")
    void add(Score score);

    /**
     * 删除成绩
     * @param courseId 课程id
     * @param studentId 学生id
     */
    @Delete("delete from score where course_id = #{courseId} and student_id = #{studentId}")
    void delete(Integer courseId, Integer studentId);

    /**
     * 批量删除成绩
     * @param courseIds 课程id的集合
     */
    void deleteByIds(List<Integer> courseIds);

    /**
     * 更新成绩
     * @param score 成绩
     */
    void update(Score score);

    /**
     * 查询成绩
     * @param courseId 课程id
     * @param studentId 学生id
     * @return 成绩
     */
    @Select("select * from score where course_id = #{courseId} and student_id = #{studentId}")
    Score select(Integer courseId, Integer studentId);

    /**
     * 根据ID查询成绩
     * @param id 成绩id
     * @return 成绩
     */
    @Select("select * from score where id = #{id}")
    Score selectById(Integer id);

    /**
     * 分页查询
     * @param courseId 课程id
     * @param studentId 学生id
     * @param term 学期
     * @return 查询列表
     */
    List<Score> list(@Param("courseId") Integer courseId, @Param("studentId") Integer studentId,@Param("term") String term);

    /**
     * 根据学生id查询成绩
     * @param studentId 学生id
     * @return 成绩列表
     */
    @Select("select * from score where student_id = #{studentId}")
    List<Score> selectByStudentId(Integer studentId);

    /**
     * 统计成绩数量
     * @return 成绩数量
     */
    @Select("select count(*) from score")
    int countScore();

    /**
     * 该生是否**已通过**某个课程代码的课（选课校验第 4 道："未修过"）。
     *
     * <p>判据是"已通过"而不是"有成绩记录"：挂科的学生应当允许**重修**，
     * 而已经过了的课再选一遍没有意义（也会让同一 course_code 出现多条成绩）。
     *
     * <p>课程代码是跨学期认课的键（见设计文档 §3.1），所以这里按
     * {@code course.course_code} 比对，而不是 course_id。
     */
    @Select("""
            select count(*)
            from score s
            join course c on s.course_id = c.id
            where s.student_id = #{studentId}
              and c.course_code = #{courseCode}
              and s.passed = 1
            """)
    int countPassedByCourseCode(@Param("studentId") String studentId,
                                @Param("courseCode") String courseCode);
}
