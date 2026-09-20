package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 教师排课申请（与开课申请分开的第二个审批流）。
 *
 * <p>设计理由（用户需求）：教师先申请开课、管理员审批后生效；「教师排课」是单独的一步，
 * 时间安排可能与开课申请**不同期**（如先批开课、后排时间）。
 * 故独立成表，避免一个表承担两个审批流。
 *
 * <p>审批时**必须先过冲突检测**（{@code ScheduleService.checkConflict}）：
 * 有冲突则不落 {@code class_time}，把冲突详情写进 {@code conflictInfo} 并抛出业务异常，
 * 让管理员看到"到底和谁撞了"。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(10)
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassTimeApply {

    private Integer id;

    /** 关联已审批的 course.id */
    private Integer courseId;

    private String teacherId;

    private Integer weekday;

    private Integer startPeriod;

    private Integer endPeriod;

    private Integer startWeek;

    private Integer endWeek;

    /** 期望教室，可空（系统推荐） */
    private Integer roomId;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    /** 冲突详情（校验失败时记录） */
    private String conflictInfo;

    private String rejectReason;

    private String reviewer;

    private LocalDateTime reviewTime;

    private LocalDateTime createTime;

    // ---- 展示用冗余字段（不落库） ----
    private String courseCode;
    private String courseName;
    private String term;
    private String teacherName;
    private String roomName;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** conflict_info 列宽（varchar(500)），写入前必须按此截断 */
    public static final int MAX_CONFLICT_INFO_LEN = 500;
}
