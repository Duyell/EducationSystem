package duyell.mapper;

import com.duyell.ClassTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 上课时间 Mapper（冲突检测的数据基础）。
 *
 * <p>⚠️ 冲突**不能靠数据库唯一约束**（区间重叠不是等值），只能在这里用查询判断。
 * 重叠判据（闭区间）：
 * {@code ct.start_period <= 需求end AND ct.end_period >= 需求start}，周次同理。
 *
 * @author duyell
 */
@Mapper
public interface ClassTimeMapper {

    @Select("select * from class_time where id = #{id}")
    ClassTime selectById(@Param("id") Integer id);

    /** 某门课的全部上课时间（含课程/教师/教室展示字段） */
    List<ClassTime> listByCourse(@Param("courseId") Integer courseId);

    /** 某教师某学期的课表 */
    List<ClassTime> listByTeacherAndTerm(@Param("teacherId") String teacherId,
                                        @Param("term") String term);

    /** 按学期（可再按星期）列全部课表，供管理员总览 */
    List<ClassTime> listAll(@Param("term") String term,
                            @Param("weekday") Integer weekday);

    /**
     * 查与候选时段冲突的既有排课。
     *
     * <p>同一个查询用两种参数调用即可区分冲突来源：
     * <ul>
     *   <li>只传 {@code teacherId} → 该教师同一时间的其它课</li>
     *   <li>只传 {@code roomId} → 该教室同一时间的其它课</li>
     * </ul>
     *
     * @param excludeCourseId 排除自己（改排课时不该和自己冲突），可空
     */
    List<ClassTime> selectConflicts(@Param("term") String term,
                                    @Param("weekday") Integer weekday,
                                    @Param("startPeriod") Integer startPeriod,
                                    @Param("endPeriod") Integer endPeriod,
                                    @Param("startWeek") Integer startWeek,
                                    @Param("endWeek") Integer endWeek,
                                    @Param("teacherId") String teacherId,
                                    @Param("roomId") Integer roomId,
                                    @Param("excludeCourseId") Integer excludeCourseId);

    /** 该时段已被占用的教室 id（去重，忽略未分配教室的排课） */
    List<Integer> listOccupiedRoomIds(@Param("term") String term,
                                      @Param("weekday") Integer weekday,
                                      @Param("startPeriod") Integer startPeriod,
                                      @Param("endPeriod") Integer endPeriod,
                                      @Param("startWeek") Integer startWeek,
                                      @Param("endWeek") Integer endWeek,
                                      @Param("excludeCourseId") Integer excludeCourseId);

    @Insert("""
            insert into class_time(course_id, weekday, start_period, end_period, start_week, end_week, room_id)
            values(#{courseId}, #{weekday}, #{startPeriod}, #{endPeriod}, #{startWeek}, #{endWeek}, #{roomId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(ClassTime classTime);

    @Delete("delete from class_time where id = #{id}")
    void deleteById(@Param("id") Integer id);

    @Select("select count(*) from class_time where course_id = #{courseId}")
    int countByCourse(@Param("courseId") Integer courseId);
}
