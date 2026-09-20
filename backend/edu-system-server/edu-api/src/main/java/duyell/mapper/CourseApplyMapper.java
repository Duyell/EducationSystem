package duyell.mapper;

import com.duyell.CourseApply;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开课申请 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface CourseApplyMapper {

    CourseApply selectById(@Param("id") Integer id);

    /**
     * 列表。三个条件均可空：
     * teacherId 用于"我的申请"，status 用于管理员待办，term 用于按学期筛。
     */
    List<CourseApply> list(@Param("teacherId") String teacherId,
                           @Param("status") String status,
                           @Param("term") String term);

    @Insert("""
            insert into course_apply(teacher_id, course_code, course_name, term, college_id,
                                     credit, class_hour, max_student,
                                     expected_weekday, expected_start_period, expected_end_period,
                                     expected_start_week, expected_end_week, prefer_room_id, status)
            values(#{teacherId}, #{courseCode}, #{courseName}, #{term}, #{collegeId},
                   #{credit}, #{classHour}, #{maxStudent},
                   #{expectedWeekday}, #{expectedStartPeriod}, #{expectedEndPeriod},
                   #{expectedStartWeek}, #{expectedEndWeek}, #{preferRoomId}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(CourseApply apply);

    /**
     * 写入审批结果。
     *
     * <p>两个可空字段一起写，是为了让结果互斥且干净：
     * 通过时把 reject_reason 清空，驳回时把 created_course_id 清空。
     */
    @Update("""
            update course_apply
            set status = #{status}, reviewer = #{reviewer}, review_time = #{reviewTime},
                created_course_id = #{createdCourseId}, reject_reason = #{rejectReason}
            where id = #{id}
            """)
    void updateReview(@Param("id") Integer id,
                      @Param("status") String status,
                      @Param("reviewer") String reviewer,
                      @Param("reviewTime") LocalDateTime reviewTime,
                      @Param("createdCourseId") Integer createdCourseId,
                      @Param("rejectReason") String rejectReason);

    /** 同一教师同一学期同一课程代码是否已有未驳回的申请（防重复提交） */
    @Select("""
            select count(*) from course_apply
            where teacher_id = #{teacherId} and term = #{term} and course_code = #{courseCode}
              and status != 'REJECTED'
            """)
    int countActiveDuplicate(@Param("teacherId") String teacherId,
                             @Param("term") String term,
                             @Param("courseCode") String courseCode);
}
