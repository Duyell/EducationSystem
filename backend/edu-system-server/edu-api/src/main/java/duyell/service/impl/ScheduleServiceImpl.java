package duyell.service.impl;

import com.duyell.ClassTime;
import com.duyell.ClassTimeApply;
import com.duyell.Course;
import com.duyell.Room;
import duyell.mapper.ClassTimeApplyMapper;
import duyell.mapper.ClassTimeMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.RoomMapper;
import duyell.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ScheduleService} 实现。
 *
 * <p>本类是全项目**冲突判据的唯一入口**：判据本身在 SQL 里（{@code ClassTimeMapper.xml}
 * 的 {@code selectConflicts} 与 {@code RoomMapper.xml} 的 {@code pickFreeRooms}），
 * 两处的重叠条件必须逐字一致，否则会出现"检测说不冲突、推荐却说教室被占"的矛盾。
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    private final ClassTimeMapper classTimeMapper;
    private final ClassTimeApplyMapper classTimeApplyMapper;
    private final RoomMapper roomMapper;
    private final CourseMapper courseMapper;

    /** 推荐候选上限的保护值：调用方给再大也不会一次吐出全表 */
    private static final int MAX_RECOMMEND = 50;

    // ==================== 校验 ====================

    @Override
    public void validateSlot(Integer weekday, Integer startPeriod, Integer endPeriod,
                             Integer startWeek, Integer endWeek) {
        if (weekday == null || weekday < ClassTime.MIN_WEEKDAY || weekday > ClassTime.MAX_WEEKDAY) {
            throw new BusinessException("星期几必须是 1~7（周一~周日）");
        }
        if (startPeriod == null || endPeriod == null) {
            throw new BusinessException("必须给出起止节次");
        }
        if (startPeriod < ClassTime.MIN_PERIOD || startPeriod > ClassTime.MAX_PERIOD
                || endPeriod < ClassTime.MIN_PERIOD || endPeriod > ClassTime.MAX_PERIOD) {
            throw new BusinessException("节次必须在 1~10 之间（一天 10 节）");
        }
        if (startPeriod > endPeriod) {
            throw new BusinessException("起始节次不能大于结束节次");
        }
        if (startWeek == null || endWeek == null) {
            throw new BusinessException("必须给出起止周次");
        }
        if (startWeek < 1 || endWeek < 1) {
            throw new BusinessException("周次必须为正数（第 1 周起）");
        }
        if (startWeek > endWeek) {
            throw new BusinessException("起始周不能大于结束周");
        }
    }

    // ==================== 冲突检测 ====================

    @Override
    public ConflictResult checkConflict(Integer excludeCourseId,
                                       String term,
                                       String teacherId,
                                       Integer roomId,
                                       Integer weekday,
                                       Integer startPeriod,
                                       Integer endPeriod,
                                       Integer startWeek,
                                       Integer endWeek) {
        if (term == null || term.isBlank()) {
            throw new BusinessException("必须指定学期");
        }
        validateSlot(weekday, startPeriod, endPeriod, startWeek, endWeek);

        // 同一个查询用两种参数调用，分别得到"教师冲突"和"教室冲突"
        List<ConflictItem> teacherConflicts = (teacherId == null || teacherId.isBlank())
                ? List.of()
                : toItems(classTimeMapper.selectConflicts(term, weekday, startPeriod, endPeriod,
                        startWeek, endWeek, teacherId, null, excludeCourseId));

        List<ConflictItem> roomConflicts = (roomId == null)
                ? List.of()
                : toItems(classTimeMapper.selectConflicts(term, weekday, startPeriod, endPeriod,
                        startWeek, endWeek, null, roomId, excludeCourseId));

        return new ConflictResult(!teacherConflicts.isEmpty() || !roomConflicts.isEmpty(),
                teacherConflicts, roomConflicts);
    }

    @Override
    public ConflictResult checkConflictForCourse(Integer courseId,
                                                Integer roomId,
                                                Integer weekday,
                                                Integer startPeriod,
                                                Integer endPeriod,
                                                Integer startWeek,
                                                Integer endWeek) {
        if (courseId == null) {
            throw new BusinessException("必须指定课程");
        }
        Course course = courseMapper.selectCourseById(courseId);
        if (course == null) {
            throw new BusinessException("课程不存在：" + courseId);
        }
        return checkConflict(null, course.getTerm(), course.getTeacherId(), roomId,
                weekday, startPeriod, endPeriod, startWeek, endWeek);
    }

    private static List<ConflictItem> toItems(List<ClassTime> rows) {        List<ConflictItem> items = new ArrayList<>(rows.size());
        for (ClassTime ct : rows) {
            items.add(new ConflictItem(ct.getId(), ct.getCourseId(), ct.getCourseCode(), ct.getCourseName(),
                    ct.getTeacherId(), ct.getTeacherName(), ct.getRoomId(), ct.getRoomName(),
                    ct.getWeekday(), ct.getStartPeriod(), ct.getEndPeriod(),
                    ct.getStartWeek(), ct.getEndWeek()));
        }
        return items;
    }

    // ==================== 教室推荐 ====================

    @Override
    public RoomRecommendation recommendRoom(String term,
                                            Integer weekday,
                                            Integer startPeriod,
                                            Integer endPeriod,
                                            Integer startWeek,
                                            Integer endWeek,
                                            Integer requiredCapacity,
                                            Integer excludeCourseId,
                                            int limit) {
        validateSlot(weekday, startPeriod, endPeriod, startWeek, endWeek);
        int capped = Math.max(1, Math.min(limit, MAX_RECOMMEND));

        List<Room> free = roomMapper.pickFreeRooms(term, weekday, startPeriod, endPeriod,
                startWeek, endWeek, requiredCapacity, null, excludeCourseId, capped);

        if (free.isEmpty()) {
            // 区分两种"没教室"，否则用户无法判断是该加教室还是该换时间
            String message;
            if (requiredCapacity != null && roomMapper.countUsableByCapacity(requiredCapacity) == 0) {
                message = "没有容量不小于 " + requiredCapacity + " 人的可用教室";
            } else {
                message = "该时段容量满足要求的教室都已被占用";
            }
            return new RoomRecommendation(false, null, null, null, List.of(), message);
        }

        Room best = free.get(0);
        return new RoomRecommendation(true, best.getId(), best.getRoomName(), best.getCapacity(), free,
                "推荐 " + best.getRoomName() + "（容量 " + best.getCapacity() + "）");
    }

    // ==================== 课表 ====================

    @Override
    public List<ClassTime> listByCourse(Integer courseId) {
        if (courseId == null) {
            return List.of();
        }
        return classTimeMapper.listByCourse(courseId);
    }

    @Override
    public List<ClassTime> listByTeacher(String teacherId, String term) {
        if (teacherId == null || teacherId.isBlank()) {
            return List.of();
        }
        return classTimeMapper.listByTeacherAndTerm(teacherId, term);
    }

    @Override
    public List<ClassTime> listAll(String term, Integer weekday) {
        return classTimeMapper.listAll(term, weekday);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeClassTime(Integer id) {
        if (id == null) {
            throw new BusinessException("缺少排课 id");
        }
        classTimeMapper.deleteById(id);
    }

    // ==================== 排课申请 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClassTimeApply submitClassTimeApply(ClassTimeApply apply, boolean requireOwner) {
        if (apply == null || apply.getCourseId() == null) {
            throw new BusinessException("必须指定课程");
        }
        Course course = courseMapper.selectCourseById(apply.getCourseId());
        if (course == null) {
            throw new BusinessException("课程不存在：" + apply.getCourseId());
        }
        if (requireOwner && !java.util.Objects.equals(course.getTeacherId(), apply.getTeacherId())) {
            throw new BusinessException("只能为自己的课程申请排课");
        }
        // 授课教师以课程为准：不接受外部传入，从根本上杜绝"申请人与授课教师不一致"
        apply.setTeacherId(course.getTeacherId());

        validateSlot(apply.getWeekday(), apply.getStartPeriod(), apply.getEndPeriod(),
                apply.getStartWeek(), apply.getEndWeek());

        apply.setStatus(ClassTimeApply.STATUS_PENDING);
        apply.setRejectReason(null);
        apply.setReviewer(null);
        apply.setReviewTime(null);

        // 提交时先检出冲突并记录，作为给教师的提示；**不阻断提交**，
        // 是否放行交给管理员审批环节的硬校验（用户流程：申请 → 检出冲突并提示 → 审批）。
        ConflictResult conflicts = checkConflict(null, course.getTerm(), course.getTeacherId(),
                apply.getRoomId(), apply.getWeekday(), apply.getStartPeriod(), apply.getEndPeriod(),
                apply.getStartWeek(), apply.getEndWeek());
        apply.setConflictInfo(conflicts.conflict() ? truncate(conflicts.describe()) : null);

        classTimeApplyMapper.add(apply);
        log.info("提交排课申请: id={}, courseId={}, teacher={}, 冲突={}",
                apply.getId(), apply.getCourseId(), apply.getTeacherId(), conflicts.total());
        return classTimeApplyMapper.selectById(apply.getId());
    }

    @Override
    public List<ClassTimeApply> listClassTimeApplies(String teacherId, String status, Integer courseId) {
        return classTimeApplyMapper.list(teacherId, status, courseId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClassTimeApply approveClassTimeApply(Integer applyId, String reviewer, Integer roomId) {
        ClassTimeApply apply = classTimeApplyMapper.selectById(applyId);
        if (apply == null) {
            throw new BusinessException("排课申请不存在：" + applyId);
        }
        if (!ClassTimeApply.STATUS_PENDING.equals(apply.getStatus())) {
            throw new BusinessException("该申请当前状态为 " + apply.getStatus() + "，不能重复审批");
        }
        Course course = courseMapper.selectCourseById(apply.getCourseId());
        if (course == null) {
            throw new BusinessException("申请关联的课程不存在，无法排课");
        }
        validateSlot(apply.getWeekday(), apply.getStartPeriod(), apply.getEndPeriod(),
                apply.getStartWeek(), apply.getEndWeek());

        // 教室优先级：审批时指定 > 申请里的期望 > 自动推荐
        Integer targetRoom = roomId != null ? roomId : apply.getRoomId();
        if (targetRoom == null) {
            RoomRecommendation rec = recommendRoom(course.getTerm(),
                    apply.getWeekday(), apply.getStartPeriod(), apply.getEndPeriod(),
                    apply.getStartWeek(), apply.getEndWeek(), course.getMaxStudent(), null, 1);
            if (!rec.found()) {
                String info = truncate("无法自动分配教室：" + rec.message());
                classTimeApplyMapper.updateConflictInfo(applyId, info);
                throw new BusinessException("审批失败：" + info);
            }
            targetRoom = rec.roomId();
        }

        // ⚠️ excludeCourseId 必须为 null：同一门课在同一时间再排一次本身就是冲突
        ConflictResult conflicts = checkConflict(null, course.getTerm(), course.getTeacherId(),
                targetRoom, apply.getWeekday(), apply.getStartPeriod(), apply.getEndPeriod(),
                apply.getStartWeek(), apply.getEndWeek());
        if (conflicts.conflict()) {
            String info = truncate(conflicts.describe());
            classTimeApplyMapper.updateConflictInfo(applyId, info);
            log.info("排课审批被冲突挡住: applyId={}, 冲突数={}", applyId, conflicts.total());
            throw new BusinessException("排课冲突，未生成课表：" + info);
        }

        ClassTime ct = new ClassTime();
        ct.setCourseId(apply.getCourseId());
        ct.setWeekday(apply.getWeekday());
        ct.setStartPeriod(apply.getStartPeriod());
        ct.setEndPeriod(apply.getEndPeriod());
        ct.setStartWeek(apply.getStartWeek());
        ct.setEndWeek(apply.getEndWeek());
        ct.setRoomId(targetRoom);
        classTimeMapper.add(ct);

        classTimeApplyMapper.updateReview(applyId, ClassTimeApply.STATUS_APPROVED, reviewer,
                LocalDateTime.now(), null, null, targetRoom);
        log.info("排课审批通过: applyId={}, 生成 class_time id={}, roomId={}", applyId, ct.getId(), targetRoom);
        return classTimeApplyMapper.selectById(applyId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectClassTimeApply(Integer applyId, String reviewer, String reason) {
        ClassTimeApply apply = classTimeApplyMapper.selectById(applyId);
        if (apply == null) {
            throw new BusinessException("排课申请不存在：" + applyId);
        }
        if (!ClassTimeApply.STATUS_PENDING.equals(apply.getStatus())) {
            throw new BusinessException("该申请当前状态为 " + apply.getStatus() + "，不能重复审批");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("驳回理由不能为空（否则教师不知道该怎么改）");
        }
        classTimeApplyMapper.updateReview(applyId, ClassTimeApply.STATUS_REJECTED, reviewer,
                LocalDateTime.now(), apply.getConflictInfo(), reason, null);
    }

    /** conflict_info 列宽限制：超长会被 MySQL 截断或报错，先按列宽切 */
    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= ClassTimeApply.MAX_CONFLICT_INFO_LEN
                ? text
                : text.substring(0, ClassTimeApply.MAX_CONFLICT_INFO_LEN);
    }
}
