package duyell.mapper;

import com.duyell.ExamSchedule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试安排 Mapper。
 *
 * <p>⚠️ 时间重叠用**半开区间**（连续时钟）：{@code existing.exam_time < newEnd
 * AND existing.exam_time + duration > newStart}。恰好相接（12:00 结束 vs 12:00 开始）
 * **不算冲突**——这与 P2 节次冲突的闭区间判据是两套语义，别互相套用。
 *
 * @author duyell
 */
@Mapper
public interface ExamScheduleMapper {

    ExamSchedule selectById(@Param("id") Integer id);

    /** 列表（term / courseId / examType / status 均可空），按考试时间升序 */
    List<ExamSchedule> list(@Param("term") String term,
                           @Param("courseId") Integer courseId,
                           @Param("examType") String examType,
                           @Param("status") Integer status);

    /** 某门课的考试安排（管理员按课程查看） */
    List<ExamSchedule> listByCourse(@Param("courseId") Integer courseId);

    /**
     * 学生的考试（学生查看页）。
     *
     * <p>范围＝**该生已选课程**的考试：没选的课不会去考，选了才要考。
     *
     * @param upcomingOnly 只看还没开考的
     */
    List<ExamSchedule> listByStudent(@Param("studentId") String studentId,
                                    @Param("term") String term,
                                    @Param("upcomingOnly") boolean upcomingOnly);

    /**
     * 考场时间冲突：同一考场、时间重叠的其它考试。
     *
     * @param excludeExamId 改考试时排除自己，可空
     */
    List<ExamSchedule> selectRoomConflicts(@Param("roomId") Integer roomId,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("excludeExamId") Integer excludeExamId);

    /**
     * 学生时间冲突：与候选考试**有共同考生**的其它考试，且时间重叠。
     *
     * <p>即"至少有一个学生同时选了这两门课，而两场考试撞在一起"。
     * 用两次 course_selection 自连接求交集，一条 SQL 出结果。
     */
    List<ExamSchedule> selectStudentConflicts(@Param("courseId") Integer courseId,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime,
                                             @Param("excludeExamId") Integer excludeExamId);

    @Insert("""
            insert into exam_schedule(course_id, exam_type, exam_time, duration_minutes,
                                      room_id, seat_range, invigilator, status, remark)
            values(#{courseId}, #{examType}, #{examTime}, #{durationMinutes},
                   #{roomId}, #{seatRange}, #{invigilator}, #{status}, #{remark})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(ExamSchedule exam);

    /** 更新（动态 SQL 见 XML） */
    void update(ExamSchedule exam);

    @Delete("delete from exam_schedule where id = #{id}")
    void deleteById(@Param("id") Integer id);

    /**
     * 某课程在某类型下是否已有别的生效考试（同一门课同一类型只应有一场）。
     *
     * @param excludeExamId 改考试时排除自己，可空（动态 SQL 见 XML）
     */
    int countActiveByCourseAndType(@Param("courseId") Integer courseId,
                                   @Param("examType") String examType,
                                   @Param("excludeExamId") Integer excludeExamId);
}
