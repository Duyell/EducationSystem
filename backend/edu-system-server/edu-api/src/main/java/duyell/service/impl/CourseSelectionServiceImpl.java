package duyell.service.impl;

import com.duyell.ClassTime;
import com.duyell.Course;
import com.duyell.CourseSelection;
import com.duyell.SelectionRound;
import duyell.mapper.ClassTimeMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.mapper.ScoreMapper;
import duyell.service.CourseSelectionService;
import duyell.service.SelectionRoundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 选课业务实现（含 P3 的六道校验链）。
 *
 * <p>并发控制沿用原有做法：事务内先对课程行加锁（{@code select ... for update}），
 * 让同一门课的选课串行执行，容量校验才不会被并发绕过；
 * 数据库唯一约束 {@code uk_course_selection_course_student} 作为最后一道兜底。
 *
 * @author duyell
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class CourseSelectionServiceImpl implements CourseSelectionService {

    private final CourseSelectionMapper courseSelectionMapper;
    private final CourseMapper courseMapper;
    private final ScoreMapper scoreMapper;
    private final ClassTimeMapper classTimeMapper;
    private final SelectionRoundService selectionRoundService;

    // ==================== 选课 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void select(Integer courseId, String studentId) {
        if (courseId == null) {
            throw new BusinessException("缺少课程 id");
        }
        // ① 行锁：同一课程的选课串行执行，防止并发超卖
        Course course = courseMapper.selectCourseByIdForUpdate(courseId);
        if (course == null) {
            throw new BusinessException("课程不存在");
        }
        // ② 重复选课（唯一约束兜底在最后）
        if (courseSelectionMapper.select(courseId, studentId) != null) {
            throw new BusinessException("该课程已选过，请勿重复选课");
        }

        // ③ 六道校验链（第 1、2 道在 statusFor 里，其余在 selectionBlocker 里）
        SelectionRoundService.SelectionStatus status =
                selectionRoundService.statusFor(studentId, course.getTerm());
        BigDecimal alreadyCredits = courseSelectionMapper.sumSelectedCredits(studentId, course.getTerm());
        String blocker = selectionBlocker(studentId, course, status, alreadyCredits);
        if (blocker != null) {
            throw new BusinessException(blocker);
        }

        // ④ 插入（数据库唯一约束兜底，异常时给出友好提示）
        SelectionRoundService.SelectionStatus hit = status;
        try {
            courseSelectionMapper.add(courseId, studentId, hit.roundId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该课程已选过，请勿重复选课");
        }
        log.info("选课成功: student={}, course={}({}), round={}, term={}",
                studentId, courseId, course.getCourseCode(), hit.roundId(), course.getTerm());
    }

    // ==================== 退课 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void drop(Integer courseId, String studentId) {
        if (courseId == null) {
            throw new BusinessException("缺少课程 id");
        }
        Course course = courseMapper.selectCourseById(courseId);
        if (course == null) {
            throw new BusinessException("课程不存在");
        }
        if (courseSelectionMapper.select(courseId, studentId) == null) {
            throw new BusinessException("你还没有选这门课");
        }
        // 用户明确：选课期间可以退，否则要等补退选期间才能退
        selectionRoundService.requireCanDrop(studentId, course.getTerm());
        courseSelectionMapper.delete(courseId, studentId);
        log.info("退课成功: student={}, course={}, term={}", studentId, courseId, course.getTerm());
    }

    // ==================== 查询 ====================

    @Override
    public List<Course> listMyCourses(String studentId) {
        List<CourseSelection> selections = courseSelectionMapper.selectByStudentId(studentId);
        if (selections.isEmpty()) {
            return List.of();
        }
        // IN 查询一次取出全部课程，避免 N+1
        List<Integer> courseIds = selections.stream()
                .map(CourseSelection::getCourseId)
                .collect(Collectors.toList());
        return courseMapper.selectByIds(courseIds);
    }

    @Override
    public List<Integer> listMyCourseIds(String studentId) {
        return courseSelectionMapper.selectByStudentId(studentId).stream()
                .map(CourseSelection::getCourseId)
                .collect(Collectors.toList());
    }

    @Override
    public List<SelectableCourse> listSelectableCourses(String studentId, String term) {
        List<Course> courses = courseMapper.listByTerm(term);
        if (courses.isEmpty()) {
            return List.of();
        }
        Set<Integer> selectedIds = new HashSet<>(listMyCourseIds(studentId));
        SelectionRoundService.SelectionStatus status = selectionRoundService.statusFor(studentId, term);
        BigDecimal alreadyCredits = courseSelectionMapper.sumSelectedCredits(studentId, term);

        List<SelectableCourse> result = new ArrayList<>(courses.size());
        for (Course course : courses) {
            boolean selected = selectedIds.contains(course.getId());
            // 已选的课不再提示原因；未选的课给出"第一道拦住它的原因"
            String reason = selected ? null : selectionBlocker(studentId, course, status, alreadyCredits);
            result.add(new SelectableCourse(course, selected, reason == null && !selected, reason));
        }
        return result;
    }

    // ==================== 校验链的唯一实现 ====================

    /**
     * 六道校验链（第 3~6 道）。
     *
     * <p>返回**不能选的原因**；能选则返回 {@code null}。
     * 「返回原因」而不是「抛异常」，是为了让"可选课程列表"能复用同一套判断
     * （列表要把原因显示在按钮旁边，而不是整页报错）。
     *
     * <p>第 1、2 道（轮次开启、适用范围）在 {@code status} 里，
     * 这里的 {@code status.canSelect()} 就是它们的结论。
     */
    private String selectionBlocker(String studentId,
                                    Course course,
                                    SelectionRoundService.SelectionStatus status,
                                    BigDecimal alreadyCredits) {
        // 第 1、2 道：轮次开启 + 适用范围
        if (!status.canSelect()) {
            return status.reason() == null ? "当前不在选课开放时间内" : status.reason();
        }

        // 第 3 道：学分上限
        if (status.maxCredits() != null) {
            BigDecimal credit = course.getCredit() == null ? BigDecimal.ZERO : course.getCredit();
            BigDecimal after = alreadyCredits.add(credit);
            if (after.compareTo(status.maxCredits()) > 0) {
                return "超过本轮学分上限（上限 " + status.maxCredits().stripTrailingZeros().toPlainString()
                        + "，已选 " + alreadyCredits.stripTrailingZeros().toPlainString()
                        + "，本课 " + credit.stripTrailingZeros().toPlainString() + "）";
            }
        }

        // 第 4 道：未修过（同 course_code 且已通过）
        String code = course.getCourseCode();
        if (code != null && !code.isBlank()
                && scoreMapper.countPassedByCourseCode(studentId, code) > 0) {
            return "你已通过课程代码为 " + code + " 的课程，无需重复修读";
        }

        // 第 5 道：无时间冲突（复用 P2 的闭区间重叠判据）
        List<ClassTime> conflicts = classTimeMapper.selectStudentConflicts(studentId, course.getId());
        if (!conflicts.isEmpty()) {
            return "与已选课程时间冲突：" + describeConflicts(conflicts);
        }

        // 第 6 道：容量未满
        Integer max = course.getMaxStudent();
        if (max != null && courseSelectionMapper.countByCourseId(course.getId()) >= max) {
            return "该课程名额已满（上限 " + max + " 人）";
        }

        return null;
    }

    /** 把冲突的既有排课写成人话：和哪门课（名称+代码）、星期几、第几节、第几周撞了 */
    private static String describeConflicts(List<ClassTime> conflicts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conflicts.size(); i++) {
            ClassTime ct = conflicts.get(i);
            if (i > 0) {
                sb.append("；");
            }
            String name = ct.getCourseName() == null ? ct.getCourseCode() : ct.getCourseName();
            sb.append('《').append(name).append('》');
            // 名称可能重复（同一门课多个教学班），带上代码才能定位到具体是哪一门
            if (ct.getCourseCode() != null && !ct.getCourseCode().isBlank()) {
                sb.append('(').append(ct.getCourseCode()).append(')');
            }
            sb.append("周").append(weekdayText(ct.getWeekday()))
              .append(' ').append(ct.getStartPeriod()).append('-').append(ct.getEndPeriod()).append("节")
              .append(" 第").append(ct.getStartWeek()).append('-').append(ct.getEndWeek()).append("周");
            if (ct.getRoomName() != null) {
                sb.append(' ').append(ct.getRoomName());
            }
        }
        return sb.toString();
    }

    private static String weekdayText(Integer weekday) {
        if (weekday == null || weekday < 1 || weekday > 7) {
            return "?";
        }
        return new String[]{"一", "二", "三", "四", "五", "六", "日"}[weekday - 1];
    }
}
