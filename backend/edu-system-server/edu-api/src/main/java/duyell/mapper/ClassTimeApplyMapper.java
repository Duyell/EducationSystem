package duyell.mapper;

import com.duyell.ClassTimeApply;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排课申请 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface ClassTimeApplyMapper {

    ClassTimeApply selectById(@Param("id") Integer id);

    /** 列表。teacherId 用于"我的申请"，status 用于管理员待办 */
    List<ClassTimeApply> list(@Param("teacherId") String teacherId,
                             @Param("status") String status,
                             @Param("courseId") Integer courseId);

    @Insert("""
            insert into class_time_apply(course_id, teacher_id, weekday, start_period, end_period,
                                         start_week, end_week, room_id, status, conflict_info)
            values(#{courseId}, #{teacherId}, #{weekday}, #{startPeriod}, #{endPeriod},
                   #{startWeek}, #{endWeek}, #{roomId}, #{status}, #{conflictInfo})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(ClassTimeApply apply);

    /**
     * 写入审批结果（两个可空字段互斥，同 CourseApplyMapper.updateReview）。
     *
     * <p>{@code roomId} 用 COALESCE 写入：审批时自动分配的教室要回填到申请行，
     * 否则申请记录永远显示"未分配教室"，课表有教室而申请说没有，审计链就断了。
     * 传 null 表示保持原值（驳回时不应抹掉教师期望的教室）。
     */
    @Update("""
            update class_time_apply
            set status = #{status}, reviewer = #{reviewer}, review_time = #{reviewTime},
                conflict_info = #{conflictInfo}, reject_reason = #{rejectReason},
                room_id = COALESCE(#{roomId}, room_id)
            where id = #{id}
            """)
    void updateReview(@Param("id") Integer id,
                      @Param("status") String status,
                      @Param("reviewer") String reviewer,
                      @Param("reviewTime") LocalDateTime reviewTime,
                      @Param("conflictInfo") String conflictInfo,
                      @Param("rejectReason") String rejectReason,
                      @Param("roomId") Integer roomId);

    /** 只记冲突详情，状态不动（审批被冲突挡住时用） */
    @Update("update class_time_apply set conflict_info = #{conflictInfo} where id = #{id}")
    void updateConflictInfo(@Param("id") Integer id,
                            @Param("conflictInfo") String conflictInfo);
}
