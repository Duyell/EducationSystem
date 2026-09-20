package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上课时间安排 —— 冲突检测的数据基础。
 *
 * <p>一门课可有**多条**（如周一 1-2 节 + 周三 3-4 节）。
 *
 * <p>⚠️ 冲突判据（docs/教务业务扩展设计.md §2.4）：
 * <pre>
 * 冲突 ⟺ 同一学期 且 星期几相同 且 节次区间有重叠 且 周次区间有重叠
 * 区间重叠的标准判据：a.start &lt;= b.end AND a.end &gt;= b.start（**闭区间**）
 * </pre>
 * 因此「第 3-4 节」与「第 4-5 节」算冲突（第 4 节重叠），而「第 1-2 节」与「第 3-4 节」不算。
 *
 * <p>⚠️ 本表**刻意不存 term**：学期由 {@code course.term} 联表得到。
 * 冗余一份 term 会在 course.term 变更后静默失真。
 *
 * <p>见 docs/教务业务扩展设计.md §3.2(5)
 *
 * @author duyell
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassTime {

    private Integer id;

    /** 关联 course.id（一次开课） */
    private Integer courseId;

    /** 1~7（周一~周日） */
    private Integer weekday;

    /** 起始节次 1~10 */
    private Integer startPeriod;

    /** 结束节次 1~10（含） */
    private Integer endPeriod;

    /** 起始周，如 1 或 9 */
    private Integer startWeek;

    /** 结束周，如 16 */
    private Integer endWeek;

    /** 关联 room.id，可空（尚未分配） */
    private Integer roomId;

    // ---- 展示用冗余字段（不落库） ----
    private String courseCode;
    private String courseName;
    private String teacherId;
    private String teacherName;
    private String term;
    private String roomName;
    private Integer roomCapacity;
    private Integer maxStudent;

    /** 一天 10 节 */
    public static final int MIN_PERIOD = 1;
    public static final int MAX_PERIOD = 10;

    public static final int MIN_WEEKDAY = 1;
    public static final int MAX_WEEKDAY = 7;
}
