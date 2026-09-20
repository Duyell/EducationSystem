package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 考试安排。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(9)。
 *
 * <p>⚠️ <b>考试的时间冲突判据与 P2 的节次冲突不同，不要互相"统一"</b>：
 * <ul>
 *   <li>节次是**离散格子**：第 3-4 节与第 4-5 节共用第 4 节 → 闭区间 `&lt;=` / `&gt;=`</li>
 *   <li>考试是**连续时钟区间**：10:00-12:00 与 12:00-14:00 只是首尾相接 → 半开区间
 *       {@code existing.start < newEnd AND existing.end > newStart}，**恰好相接不算冲突**</li>
 * </ul>
 * 拿"第 4 节算谁的"那套来判考试，会把连着考的两场误判成冲突。
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamSchedule {

    private Integer id;

    /** 关联 course.id */
    private Integer courseId;

    /** FINAL 期末 / MAKEUP 补考 / MIDTERM 期中 */
    private String examType;

    /** 考试开始时间 */
    private LocalDateTime examTime;

    /** 考试时长（分钟） */
    private Integer durationMinutes;

    /** 考场（关联 room.id），可空=待定 */
    private Integer roomId;

    /** 座位/考场号段，如 A区01-30 */
    private String seatRange;

    /** 监考教师（可多人，逗号分隔） */
    private String invigilator;

    /** 1=有效 0=作废 */
    private Integer status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---- 展示用冗余字段（不落库） ----
    private String courseCode;
    private String courseName;
    private String teacherName;
    private String roomName;
    private String term;

    public static final String TYPE_FINAL = "FINAL";
    public static final String TYPE_MAKEUP = "MAKEUP";
    public static final String TYPE_MIDTERM = "MIDTERM";

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_VOID = 0;

    /**
     * 结束时刻 = 开始 + 时长。
     *
     * <p>刻意**不**写成 {@code getEndTime()}：那会被 Jackson 当成属性序列化出去，
     * 而这个值是从另两个字段算出来的，放报文里只会让人以为库里真有这一列
     * （且改完 examTime 忘了改它就会不一致）。需要就在调用方算。
     */
    public LocalDateTime endTime() {
        if (examTime == null) {
            return null;
        }
        return examTime.plusMinutes(durationMinutes == null ? 0 : durationMinutes);
    }

    /** 是否还没开考（同上：不是 getter，故不进报文） */
    public boolean upcoming() {
        return examTime != null && examTime.isAfter(LocalDateTime.now());
    }

    /**
     * 考试类型的中文名。
     *
     * <p>⚠️ 这个**故意**写成 {@code getXxx()}：它会被 Jackson 序列化成 {@code typeLabel}，
     * 前端与 Agent 工具可以直接用，不必各自维护一份"FINAL→期末"的映射。
     * （对照上面两个方法：不带 get/is 前缀的就不会进报文——这个区别在 P1/P3 已经踩过两次。）
     */
    public String getTypeLabel() {
        if (TYPE_FINAL.equals(examType)) {
            return "期末";
        }
        if (TYPE_MAKEUP.equals(examType)) {
            return "补考";
        }
        if (TYPE_MIDTERM.equals(examType)) {
            return "期中";
        }
        return examType;
    }
}
