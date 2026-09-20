package duyell.service.impl;

import com.duyell.Course;
import com.duyell.CourseApply;
import com.duyell.Teacher;
import duyell.mapper.CourseApplyMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.TeacherMapper;
import duyell.service.CourseApplyService;
import duyell.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link CourseApplyService} 实现。
 *
 * <p>状态机刻意很窄：只有 {@code PENDING → APPROVED} 与 {@code PENDING → REJECTED}，
 * 且都要求当前是 PENDING —— 防止重复审批、防止把已驳回的申请"复活"。
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseApplyServiceImpl implements CourseApplyService {

    private final CourseApplyMapper courseApplyMapper;
    private final CourseMapper courseMapper;
    private final TeacherMapper teacherMapper;
    /** 只用于校验申请里填写的"期望时间"结构是否合法，不在这里做冲突判定 */
    private final ScheduleService scheduleService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CourseApply submit(CourseApply apply) {
        if (apply == null) {
            throw new BusinessException("申请内容不能为空");
        }
        if (apply.getTeacherId() == null || apply.getTeacherId().isBlank()) {
            throw new BusinessException("缺少申请人");
        }
        Teacher teacher = teacherMapper.selectTeacherByTeacherId(apply.getTeacherId());
        if (teacher == null) {
            throw new BusinessException("教师不存在：" + apply.getTeacherId());
        }
        if (apply.getCourseCode() == null || apply.getCourseCode().isBlank()) {
            throw new BusinessException("课程代码不能为空（培养计划与已修判定都以课程代码关联）");
        }
        if (apply.getCourseName() == null || apply.getCourseName().isBlank()) {
            throw new BusinessException("课程名称不能为空");
        }
        if (apply.getTerm() == null || apply.getTerm().isBlank()) {
            throw new BusinessException("必须指定开课学期");
        }
        if (apply.getMaxStudent() == null || apply.getMaxStudent() < 1) {
            throw new BusinessException("选课容量必须大于 0（容量 = 教学班容量）");
        }
        if (apply.getCredit() == null || apply.getCredit().signum() < 0) {
            throw new BusinessException("学分不能为负");
        }
        if (apply.getClassHour() == null || apply.getClassHour() < 0) {
            throw new BusinessException("课时不能为负");
        }

        // 开课学院：未指定则取教师所属学院（course.college_id 是 NOT NULL，必须有值）
        if (apply.getCollegeId() == null) {
            apply.setCollegeId(teacher.getCollegeId());
        }
        if (apply.getCollegeId() == null) {
            throw new BusinessException("教师未归属学院，无法开课（请先在教师管理中补全学院）");
        }

        // 期望时间可选；填了就要结构合法
        if (apply.getExpectedWeekday() != null) {
            scheduleService.validateSlot(apply.getExpectedWeekday(), apply.getExpectedStartPeriod(),
                    apply.getExpectedEndPeriod(), apply.getExpectedStartWeek(), apply.getExpectedEndWeek());
        }

        if (courseApplyMapper.countActiveDuplicate(apply.getTeacherId(), apply.getTerm(),
                apply.getCourseCode()) > 0) {
            throw new BusinessException("你在 " + apply.getTerm() + " 已提交过课程 "
                    + apply.getCourseCode() + " 的开课申请（未被驳回），请勿重复提交");
        }

        apply.setStatus(CourseApply.STATUS_PENDING);
        apply.setRejectReason(null);
        apply.setReviewer(null);
        apply.setReviewTime(null);
        apply.setCreatedCourseId(null);

        courseApplyMapper.add(apply);
        log.info("提交开课申请: id={}, teacher={}, code={}, term={}",
                apply.getId(), apply.getTeacherId(), apply.getCourseCode(), apply.getTerm());
        return courseApplyMapper.selectById(apply.getId());
    }

    @Override
    public List<CourseApply> listMine(String teacherId) {
        if (teacherId == null || teacherId.isBlank()) {
            return List.of();
        }
        return courseApplyMapper.list(teacherId, null, null);
    }

    @Override
    public List<CourseApply> listAll(String status, String term) {
        return courseApplyMapper.list(null, status, term);
    }

    @Override
    public CourseApply get(Integer applyId) {
        return applyId == null ? null : courseApplyMapper.selectById(applyId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CourseApply approve(Integer applyId, String reviewer) {
        CourseApply apply = requirePending(applyId);

        // 审批通过才真正生成开课记录：课程代码沿用申请里的，保证与培养计划能关联上
        Course course = new Course();
        course.setCourseCode(apply.getCourseCode());
        course.setCourseName(apply.getCourseName());
        course.setTeacherId(apply.getTeacherId());
        course.setCollegeId(apply.getCollegeId());
        course.setTerm(apply.getTerm());
        course.setCredit(apply.getCredit());
        course.setClassHour(apply.getClassHour());
        course.setMaxStudent(apply.getMaxStudent());
        courseMapper.add(course);

        courseApplyMapper.updateReview(applyId, CourseApply.STATUS_APPROVED, reviewer,
                LocalDateTime.now(), course.getId(), null);
        log.info("开课申请审批通过: applyId={}, 生成 course id={}, code={}",
                applyId, course.getId(), apply.getCourseCode());
        return courseApplyMapper.selectById(applyId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Integer applyId, String reviewer, String reason) {
        requirePending(applyId);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("驳回理由不能为空（否则教师不知道该怎么改）");
        }
        courseApplyMapper.updateReview(applyId, CourseApply.STATUS_REJECTED, reviewer,
                LocalDateTime.now(), null, reason);
    }

    /** 取出且必须是 PENDING，否则拒绝 —— 状态机的唯一入口 */
    private CourseApply requirePending(Integer applyId) {
        if (applyId == null) {
            throw new BusinessException("缺少申请 id");
        }
        CourseApply apply = courseApplyMapper.selectById(applyId);
        if (apply == null) {
            throw new BusinessException("开课申请不存在：" + applyId);
        }
        if (!CourseApply.STATUS_PENDING.equals(apply.getStatus())) {
            throw new BusinessException("该申请当前状态为 " + apply.getStatus() + "，不能重复审批");
        }
        return apply;
    }
}
