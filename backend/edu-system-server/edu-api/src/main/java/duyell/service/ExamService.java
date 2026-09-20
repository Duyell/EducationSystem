package duyell.service;

import com.duyell.ExamSchedule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试安排服务。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(9)、§5 P4。
 *
 * <p><b>时间冲突判据（与 P2 节次冲突不同！）</b>：考试是连续时钟区间，
 * 用**半开区间** {@code existing.start < newEnd AND existing.end > newStart}，
 * 恰好相接（前一场 12:00 结束、后一场 12:00 开始）**不算冲突**。
 * 两类冲突：同一考场被占、以及**有共同考生**的两门课撞在一起。
 *
 * @author duyell
 */
public interface ExamService {

    /** 冲突检测结果：考场冲突与考生冲突分开，便于前端分别提示 */
    record ConflictResult(boolean conflict,
                          List<ExamSchedule> roomConflicts,
                          List<ExamSchedule> studentConflicts) {

        public int total() {
            return roomConflicts.size() + studentConflicts.size();
        }

        /** 可直接展示的中文说明（也会进业务异常消息） */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (!roomConflicts.isEmpty()) {
                sb.append("该考场此时段已有考试：").append(join(roomConflicts));
            }
            if (!studentConflicts.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("有学生同时要考：").append(join(studentConflicts));
            }
            return sb.toString();
        }

        private static String join(List<ExamSchedule> items) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                ExamSchedule e = items.get(i);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append('《').append(e.getCourseCode()).append(' ').append(e.getCourseName()).append('》');
                if (e.getTypeLabel() != null) {
                    sb.append(e.getTypeLabel());
                }
                sb.append(formatRange(e));
            }
            return sb.toString();
        }

        private static String formatRange(ExamSchedule e) {
            if (e.getExamTime() == null) {
                return "";
            }
            String start = e.getExamTime().toString().replace('T', ' ');
            LocalDateTime end = e.endTime();
            String endText = end == null ? "" : "-" + end.toLocalTime().toString();
            return "（" + start + endText + "）";
        }
    }

    // ---------- 查询 ----------

    List<ExamSchedule> list(String term, Integer courseId, String examType, Integer status);

    ExamSchedule get(Integer id);

    List<ExamSchedule> listByCourse(Integer courseId);

    /** 学生的考试＝其**已选课程**的考试 */
    List<ExamSchedule> listByStudent(String studentId, String term, boolean upcomingOnly);

    // ---------- 冲突检测 ----------

    /**
     * 检测候选考试是否与既有安排冲突。
     *
     * @param roomId        可空（待定考场则不查考场维度）
     * @param excludeExamId 改考试时排除自己，可空
     */
    ConflictResult checkConflict(Integer courseId,
                                 LocalDateTime examTime,
                                 Integer durationMinutes,
                                 Integer roomId,
                                 Integer excludeExamId);

    // ---------- 管理员维护 ----------

    ExamSchedule create(ExamSchedule exam);

    void update(ExamSchedule exam);

    void remove(Integer id);
}
