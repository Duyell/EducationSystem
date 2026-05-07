package duyell.mapper;

import com.duyell.TeacherEvaluation;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface EvaluationMapper {

    @Insert("insert into teacher_evaluation(course_id, student_id, teacher_id, score, content) " +
            "values(#{courseId}, #{studentId}, #{teacherId}, #{score}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(TeacherEvaluation evaluation);

    @Select("select * from teacher_evaluation where course_id = #{courseId} and student_id = #{studentId}")
    TeacherEvaluation selectByCourseAndStudent(Integer courseId, String studentId);

    List<TeacherEvaluation> list(@Param("courseId") Integer courseId,
                                  @Param("studentId") String studentId,
                                  @Param("teacherId") String teacherId);

    @Select("select count(*) from teacher_evaluation where teacher_id = #{teacherId}")
    int countByTeacherId(String teacherId);

    @Select("select avg(score) from teacher_evaluation where teacher_id = #{teacherId}")
    Double avgScoreByTeacherId(String teacherId);
}
