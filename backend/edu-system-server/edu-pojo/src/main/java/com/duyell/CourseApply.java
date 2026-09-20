package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 教师开课申请（审批流）。
 *
 * <p>用户明确的业务流程：
 * <pre>
 * 教师提交申请 → 管理员审批 → 审批通过才生效 → 且【下次开启选课】时学生才能选
 * </pre>
 * 因此「审批通过」与「可被选」是**两个状态**：课程可被选需同时满足
 * 「已审批通过」且「处于某个已开启的选课轮次范围内」（轮次见 P3）。
 *
 * <p>{@code maxStudent} 既是选课容量也是教学班容量（用户确认二者一致）。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(6)
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseApply {

    private Integer id;

    /** 申请人（教师工号） */
    private String teacherId;

    /** 拟开课程代码 */
    private String courseCode;

    private String courseName;

    /** 拟开学期 */
    private String term;

    /** 开课学院，可空（默认取教师所属学院） */
    private Integer collegeId;

    private BigDecimal credit;

    private Integer classHour;

    /** 选课容量 = 教学班容量 */
    private Integer maxStudent;

    // ---- 教师期望时间（可空；审批通过后作为排课申请的默认值） ----
    private Integer expectedWeekday;
    private Integer expectedStartPeriod;
    private Integer expectedEndPeriod;
    private Integer expectedStartWeek;
    private Integer expectedEndWeek;

    /** 期望教室，可空（由系统分配） */
    private Integer preferRoomId;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    private String rejectReason;

    private String reviewer;

    private LocalDateTime reviewTime;

    /** 审批通过后生成/关联的 course.id */
    private Integer createdCourseId;

    private LocalDateTime createTime;

    // ---- 展示用冗余字段（不落库） ----
    private String teacherName;
    private String preferRoomName;
    private String collegeName;
    private String createdCourseCode;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
}
