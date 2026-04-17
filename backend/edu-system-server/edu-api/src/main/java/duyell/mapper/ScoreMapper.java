package duyell.mapper;

import com.duyell.Score;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
    @Insert("insert into score(course_id, student_id, usual_score,exam_score,total_score) values(#{courseId},#{studentId},#{usual_score},#{exam_score},#{total_score})")
    void add(Score score);

    /**
     * 删除成绩
     * @param courseId 课程id
     * @param studentId 学生id
     */
    @Delete("delete from score where course_id = #{courseId} and student_id = #{studentId}")
    void delete(Integer courseId, Integer studentId);

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
     * 分页查询
     * @return 查询列表
     */
    @Select("select * from score")
    List<Score> list();

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
}
