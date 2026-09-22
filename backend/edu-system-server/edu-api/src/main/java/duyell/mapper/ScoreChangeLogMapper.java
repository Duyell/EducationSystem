package duyell.mapper;

import com.duyell.ScoreChangeLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 成绩变更日志（{@code score_change_log}）。
 *
 * <p>只写不删：日志的价值来自"永远在那里"。查询按学号/课程/操作人过滤，按时间倒序。
 *
 * @author duyell
 */
@Mapper
public interface ScoreChangeLogMapper {

    @Insert("insert into score_change_log(operator_id, operator_role, source, operation, score_id, "
            + "course_id, student_id, "
            + "before_usual, before_exam, before_makeup, before_total, before_passed, "
            + "after_usual, after_exam, after_makeup, after_total, after_passed) "
            + "values(#{operatorId}, #{operatorRole}, #{source}, #{operation}, #{scoreId}, "
            + "#{courseId}, #{studentId}, "
            + "#{beforeUsual}, #{beforeExam}, #{beforeMakeup}, #{beforeTotal}, #{beforePassed}, "
            + "#{afterUsual}, #{afterExam}, #{afterMakeup}, #{afterTotal}, #{afterPassed})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(ScoreChangeLog log);

    /**
     * 按条件查询（全部条件可为空＝不筛），按时间倒序、id 倒序。
     *
     * <p>连表带出课程名与学号对应姓名，便于管理员直接阅读；课程/学生被删也不影响日志本身。
     */
    @Select("""
            <script>
            select l.*, c.course_name as courseName, c.course_code as courseCode,
                   s.student_name as studentName
            from score_change_log l
            left join course c on l.course_id = c.id
            left join student s on l.student_id = s.student_id
            <where>
              <if test="studentId != null and studentId != ''">and l.student_id = #{studentId}</if>
              <if test="courseId != null">and l.course_id = #{courseId}</if>
              <if test="operatorId != null and operatorId != ''">and l.operator_id = #{operatorId}</if>
            </where>
            order by l.create_time desc, l.id desc
            </script>
            """)
    List<ScoreChangeLog> list(@Param("studentId") String studentId,
                              @Param("courseId") Integer courseId,
                              @Param("operatorId") String operatorId);

    @Select("""
            <script>
            select count(*) from score_change_log l
            <where>
              <if test="studentId != null and studentId != ''">and l.student_id = #{studentId}</if>
              <if test="courseId != null">and l.course_id = #{courseId}</if>
              <if test="operatorId != null and operatorId != ''">and l.operator_id = #{operatorId}</if>
            </where>
            </script>
            """)
    long count(@Param("studentId") String studentId,
               @Param("courseId") Integer courseId,
               @Param("operatorId") String operatorId);
}
