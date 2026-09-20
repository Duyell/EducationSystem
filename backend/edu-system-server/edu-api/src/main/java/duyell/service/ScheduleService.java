package duyell.service;

import com.duyell.ClassTime;
import com.duyell.ClassTimeApply;
import com.duyell.Room;

import java.util.List;

/**
 * 排课服务：时间冲突检测、教室推荐、排课申请审批。
 *
 * <p><b>冲突判据</b>（docs/教务业务扩展设计.md §2.4，用户确认，不得改动）：
 * <pre>
 * 冲突 ⟺ 同一学期 且 星期几相同 且 节次区间有重叠 且 周次区间有重叠
 * 区间重叠判据（闭区间）：a.start &lt;= b.end AND a.end &gt;= b.start
 * </pre>
 * 「第 1-2 节」与「第 3-4 节」**不算**冲突（相接）；「第 3-4 节」与「第 4-5 节」**算**冲突。
 *
 * <p>学期一律取自 {@code course.term}：{@code class_time} 刻意不冗余 term。
 *
 * @author duyell
 */
public interface ScheduleService {

    /** 一条冲突记录（用于告诉用户"到底和谁撞了"） */
    record ConflictItem(Integer classTimeId,
                        Integer courseId,
                        String courseCode,
                        String courseName,
                        String teacherId,
                        String teacherName,
                        Integer roomId,
                        String roomName,
                        Integer weekday,
                        Integer startPeriod,
                        Integer endPeriod,
                        Integer startWeek,
                        Integer endWeek) {
    }

    /** 冲突检测结果。教师冲突与教室冲突分开，便于前端分别提示 */
    record ConflictResult(boolean conflict,
                          List<ConflictItem> teacherConflicts,
                          List<ConflictItem> roomConflicts) {

        public int total() {
            return teacherConflicts.size() + roomConflicts.size();
        }

        /** 供业务异常使用的可读描述（会被写进 class_time_apply.conflict_info） */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (!teacherConflicts.isEmpty()) {
                sb.append("与教师已有课程冲突：");
                sb.append(join(teacherConflicts));
            }
            if (!roomConflicts.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("与教室占用冲突：");
                sb.append(join(roomConflicts));
            }
            return sb.toString();
        }

        private static String join(List<ConflictItem> items) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                ConflictItem it = items.get(i);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(it.courseCode()).append(' ').append(it.courseName())
                  .append("（周").append(it.weekday())
                  .append(' ').append(it.startPeriod()).append('-').append(it.endPeriod()).append("节")
                  .append(" 第").append(it.startWeek()).append('-').append(it.endWeek()).append("周");
                if (it.roomName() != null) {
                    sb.append(' ').append(it.roomName());
                }
                sb.append('）');
            }
            return sb.toString();
        }
    }

    /** 教室推荐结果 */
    record RoomRecommendation(boolean found,
                              Integer roomId,
                              String roomName,
                              Integer capacity,
                              List<Room> candidates,
                              String message) {
    }

    /**
     * 校验时间区间的结构合法性。
     *
     * <p>只校验结构（取值范围、起止顺序），**不校验"合法课次块"**：
     * 用户只描述了上午的 1-2 / 3-4 / 3-4-5，未给出下午的排法，
     * 故此处不做块模式约束（记为【假设】，实施前可复核）。
     *
     * @throws utils.BusinessException 结构非法时
     */
    void validateSlot(Integer weekday, Integer startPeriod, Integer endPeriod,
                      Integer startWeek, Integer endWeek);

    /**
     * 冲突检测。
     *
     * @param excludeCourseId 排除某门课（仅"改排已有课"时用）。
     *                        ⚠️ 审批新增排课时**必须传 null**：同一门课在同一时间再排一次
     *                        本身就是冲突，不该被排除掉。
     * @param teacherId       只传教师 → 查"该教师的其它课"；可空
     * @param roomId          只传教室 → 查"该教室的其它课"；可空
     */
    ConflictResult checkConflict(Integer excludeCourseId,
                                 String term,
                                 String teacherId,
                                 Integer roomId,
                                 Integer weekday,
                                 Integer startPeriod,
                                 Integer endPeriod,
                                 Integer startWeek,
                                 Integer endWeek);

    /**
     * 冲突检测（按课程自动取学期与授课教师）。
     *
     * <p>给前端"排课前先试算"用：只需传课程与候选时段，学期与教师从课程推导，
     * 避免调用方自己拼 term/teacherId 拼错导致漏检。
     *
     * @param roomId 候选教室，可空（只查教师冲突）
     */
    ConflictResult checkConflictForCourse(Integer courseId,
                                         Integer roomId,
                                         Integer weekday,
                                         Integer startPeriod,
                                         Integer endPeriod,
                                         Integer startWeek,
                                         Integer endWeek);

    /**
     * 推荐该时段空闲的教室。
     *
     * @param requiredCapacity 容量下限（一般取课程 max_student），可空
     * @param limit            最多返回几个候选
     */
    RoomRecommendation recommendRoom(String term,
                                     Integer weekday,
                                     Integer startPeriod,
                                     Integer endPeriod,
                                     Integer startWeek,
                                     Integer endWeek,
                                     Integer requiredCapacity,
                                     Integer excludeCourseId,
                                     int limit);

    // ---------- 课表 ----------

    List<ClassTime> listByCourse(Integer courseId);

    List<ClassTime> listByTeacher(String teacherId, String term);

    List<ClassTime> listAll(String term, Integer weekday);

    void removeClassTime(Integer id);

    // ---------- 排课申请（与开课申请分开的第二个审批流） ----------

    /**
     * 教师提交排课申请。
     *
     * <p>{@code teacherId} **由服务从课程推导**（= course.teacher_id），不接受外部传入，
     * 从根本上避免"申请人与授课教师不一致"。教师角色另由 {@code requireOwner} 校验归属。
     *
     * <p>提交时**先检出冲突并记录到 conflict_info 返回给教师提示**，但不阻断提交——
     * 是否放行由管理员在审批环节决定（审批环节有硬校验）。
     */
    ClassTimeApply submitClassTimeApply(ClassTimeApply apply, boolean requireOwner);

    List<ClassTimeApply> listClassTimeApplies(String teacherId, String status, Integer courseId);

    /**
     * 管理员审批排课申请。
     *
     * <p>硬校验：有冲突则**不落 class_time**，把冲突详情写进 conflict_info 并抛业务异常，
     * 状态保持 PENDING（便于教师改时间后重新提交/管理员重试）。
     *
     * @param roomId 指定教室；为空则用申请里的期望教室；仍为空则自动推荐
     */
    ClassTimeApply approveClassTimeApply(Integer applyId, String reviewer, Integer roomId);

    /** 驳回排课申请 */
    void rejectClassTimeApply(Integer applyId, String reviewer, String reason);
}
