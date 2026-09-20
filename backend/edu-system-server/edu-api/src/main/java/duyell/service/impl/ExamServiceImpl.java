package duyell.service.impl;

import com.duyell.Course;
import com.duyell.ExamSchedule;
import com.duyell.Room;
import duyell.mapper.CourseMapper;
import duyell.mapper.ExamScheduleMapper;
import duyell.mapper.RoomMapper;
import duyell.service.ExamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * {@link ExamService} 实现。
 *
 * <p>本类是考试时间冲突判据的**唯一入口**：判据写在
 * {@code ExamScheduleMapper.xml} 的 {@code selectRoomConflicts} /
 * {@code selectStudentConflicts} 里，用的是**半开区间**
 * （连续时钟），与 P2 节次的闭区间判据是两套语义，**不要互相"统一"**。
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamServiceImpl implements ExamService {

    private final ExamScheduleMapper examMapper;
    private final CourseMapper courseMapper;
    private final RoomMapper roomMapper;

    private static final Set<String> TYPES = Set.of(
            ExamSchedule.TYPE_FINAL, ExamSchedule.TYPE_MAKEUP, ExamSchedule.TYPE_MIDTERM);

    /** 时长上限：一场考试不该超过 8 小时，防止误填把时间算出天际 */
    private static final int MAX_DURATION_MINUTES = 8 * 60;

    // ==================== 查询 ====================

    @Override
    public List<ExamSchedule> list(String term, Integer courseId, String examType, Integer status) {
        return examMapper.list(term, courseId, examType, status);
    }

    @Override
    public ExamSchedule get(Integer id) {
        return id == null ? null : examMapper.selectById(id);
    }

    @Override
    public List<ExamSchedule> listByCourse(Integer courseId) {
        return courseId == null ? List.of() : examMapper.listByCourse(courseId);
    }

    @Override
    public List<ExamSchedule> listByStudent(String studentId, String term, boolean upcomingOnly) {
        if (studentId == null || studentId.isBlank()) {
            return List.of();
        }
        return examMapper.listByStudent(studentId, term, upcomingOnly);
    }

    // ==================== 冲突检测 ====================

    @Override
    public ConflictResult checkConflict(Integer courseId,
                                        LocalDateTime examTime,
                                        Integer durationMinutes,
                                        Integer roomId,
                                        Integer excludeExamId) {
        if (examTime == null) {
            throw new BusinessException("必须给出考试开始时间");
        }
        if (durationMinutes == null || durationMinutes <= 0) {
            throw new BusinessException("考试时长必须大于 0 分钟");
        }
        LocalDateTime endTime = examTime.plusMinutes(durationMinutes);

        List<ExamSchedule> roomConflicts = roomId == null
                ? List.of()
                : examMapper.selectRoomConflicts(roomId, examTime, endTime, excludeExamId);

        List<ExamSchedule> studentConflicts = courseId == null
                ? List.of()
                : examMapper.selectStudentConflicts(courseId, examTime, endTime, excludeExamId);

        return new ConflictResult(!roomConflicts.isEmpty() || !studentConflicts.isEmpty(),
                roomConflicts, studentConflicts);
    }

    // ==================== 管理员维护 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExamSchedule create(ExamSchedule exam) {
        validate(exam);
        if (examMapper.countActiveByCourseAndType(exam.getCourseId(), exam.getExamType(), null) > 0) {
            throw new BusinessException("该课程已有生效中的"
                    + exam.getTypeLabel() + "考试安排，请直接修改原记录");
        }
        if (exam.getStatus() == null) {
            exam.setStatus(ExamSchedule.STATUS_ACTIVE);
        }
        requireNoConflict(exam, null);
        examMapper.add(exam);
        log.info("新增考试安排: id={}, courseId={}, type={}, time={}",
                exam.getId(), exam.getCourseId(), exam.getExamType(), exam.getExamTime());
        return examMapper.selectById(exam.getId());
    }

    /**
     * 修改考试安排。
     *
     * <p><b>更新语义</b>（与 XML 里的注释一致，别只改一边）：
     * <ul>
     *   <li>必填字段（课程/类型/时间/时长）不传则**继承库里的值**——
     *       只改座位号时不必重发时间；不补齐就会拿 null 去校验，误报"必须给出考试开始时间"</li>
     *   <li>可选字段（考场/座位号段/监考人/备注）**整替换**：传 null 即清空（考场待定）</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ExamSchedule exam) {
        if (exam.getId() == null) {
            throw new BusinessException("缺少考试 id");
        }
        ExamSchedule existing = examMapper.selectById(exam.getId());
        if (existing == null) {
            throw new BusinessException("考试安排不存在：" + exam.getId());
        }
        // 局部更新时把缺的字段补全，否则校验与冲突检测会拿到 null
        if (exam.getCourseId() == null) {
            exam.setCourseId(existing.getCourseId());
        }
        if (exam.getExamType() == null) {
            exam.setExamType(existing.getExamType());
        }
        if (exam.getExamTime() == null) {
            exam.setExamTime(existing.getExamTime());
        }
        if (exam.getDurationMinutes() == null) {
            exam.setDurationMinutes(existing.getDurationMinutes());
        }
        validate(exam);
        if (examMapper.countActiveByCourseAndType(exam.getCourseId(), exam.getExamType(), exam.getId()) > 0) {
            throw new BusinessException("该课程已有另一条生效中的"
                    + exam.getTypeLabel() + "考试安排");
        }
        requireNoConflict(exam, exam.getId());
        examMapper.update(exam);
        log.info("更新考试安排: id={}", exam.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Integer id) {
        if (id == null) {
            throw new BusinessException("缺少考试 id");
        }
        examMapper.deleteById(id);
        log.info("删除考试安排: id={}", id);
    }

    // ==================== 内部 ====================

    /** 冲突即拒绝：用户确认的原则是"不允许冲突"，排考试同样适用 */
    private void requireNoConflict(ExamSchedule exam, Integer excludeExamId) {
        ConflictResult conflicts = checkConflict(exam.getCourseId(), exam.getExamTime(),
                exam.getDurationMinutes(), exam.getRoomId(), excludeExamId);
        if (conflicts.conflict()) {
            throw new BusinessException("考试安排冲突：" + conflicts.describe());
        }
    }

    private void validate(ExamSchedule exam) {
        if (exam == null) {
            throw new BusinessException("考试内容不能为空");
        }
        if (exam.getCourseId() == null) {
            throw new BusinessException("必须指定课程");
        }
        Course course = courseMapper.selectCourseById(exam.getCourseId());
        if (course == null) {
            throw new BusinessException("课程不存在：" + exam.getCourseId());
        }
        if (exam.getExamType() == null || !TYPES.contains(exam.getExamType())) {
            throw new BusinessException("考试类型只能是 FINAL（期末）/ MAKEUP（补考）/ MIDTERM（期中）");
        }
        if (exam.getExamTime() == null) {
            throw new BusinessException("必须给出考试开始时间");
        }
        Integer duration = exam.getDurationMinutes();
        if (duration == null || duration <= 0) {
            throw new BusinessException("考试时长必须大于 0 分钟");
        }
        if (duration > MAX_DURATION_MINUTES) {
            throw new BusinessException("考试时长不能超过 " + MAX_DURATION_MINUTES + " 分钟");
        }
        if (exam.getRoomId() != null) {
            Room room = roomMapper.selectById(exam.getRoomId());
            if (room == null) {
                throw new BusinessException("考场不存在：" + exam.getRoomId());
            }
        }
        if (exam.getStatus() != null
                && exam.getStatus() != ExamSchedule.STATUS_ACTIVE
                && exam.getStatus() != ExamSchedule.STATUS_VOID) {
            throw new BusinessException("状态只能是 1（有效）或 0（作废）");
        }
    }
}
