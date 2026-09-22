package duyell.mapper;

import com.duyell.AcademicWarning;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 学业预警已读水位线（{@code academic_warning}）。
 *
 * <p>只有两个操作：查某生**最高**水位线、插入一条新水位线。
 * 唯一的业务约束（同一学生的同一水位线只记一次）由唯一索引
 * {@code uk_warning_student_credits} 保证，插入端捕获重复即可视为幂等成功。
 *
 * @author duyell
 */
@Mapper
public interface AcademicWarningMapper {

    /**
     * 取某生已记录的最高水位线。
     *
     * <p>按 {@code failed_credits} 倒序再按 id 倒序：学分相同的情况下以最新那次为准，
     * 这样返回的 {@code readAt} 是"最近一次确认时间"。
     *
     * @param studentId 学号
     * @return 最高水位线；从未确认过则返回 {@code null}
     */
    @Select("select * from academic_warning where student_id = #{studentId} "
            + "order by failed_credits desc, id desc limit 1")
    AcademicWarning selectLatest(@Param("studentId") String studentId);

    /**
     * 记录一条水位线（学生确认预警时调用）。
     *
     * @param warning 水位线记录
     */
    @Insert("insert into academic_warning(student_id, failed_credits, failed_course_count, threshold, read_at) "
            + "values(#{studentId}, #{failedCredits}, #{failedCourseCount}, #{threshold}, #{readAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(AcademicWarning warning);
}
