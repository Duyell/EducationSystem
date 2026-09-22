package duyell.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学业预警（{@code docs/policies/01-学籍管理规定.md} §5）。
 *
 * <p><b>口径</b>：学生在校期间**未通过课程**（考核不及格且补考未通过）的学分累计达到阈值即触发预警。
 * 「是否通过」委托 {@link GpaService#isPassed} 判定，不在此重复实现补考规则。
 *
 * <p><b>通知方式</b>：只弹一次、学生可标记已读。水位线记在 {@code academic_warning} 表：
 * 学生确认时把**当时的**未通过学分记下来；之后只有当前值**超过**该水位线才再次提示
 * （即"情况变严重才再弹"）。因此本服务只负责"算"与"记水位线"，不负责"是否已读"的状态机。
 *
 * @author duyell
 */
public interface AcademicWarningService {

    /** 触发预警的一门未通过课程 */
    record FailedCourse(Integer courseId, String courseCode, String courseName,
                        BigDecimal credit, BigDecimal totalScore, BigDecimal makeupScore) {
    }

    /**
     * 学业预警状态。
     *
     * <p>⚠️ 只放 record 组件、不放派生方法：record 的派生方法**不会**被 Jackson 序列化
     * （本项目已因此出过两次事故），需要给前端的字段一律显式列在这里。
     *
     * @param warned              当前是否达到预警条件
     * @param shouldNotify        是否需要向学生弹通知（达到条件 **且** 超过已确认的水位线）
     * @param failedCredits       未通过课程学分累计
     * @param failedCourseCount   未通过课程门数
     * @param threshold           当前阈值（配置项）
     * @param lastNotifiedCredits 上次已确认的水位线（从未确认过为 {@code null}）
     * @param lastReadAt          上次确认时间（从未确认过为 {@code null}）
     * @param courses             未通过课程清单（按课程代码升序，便于稳定输出与断言）
     */
    record WarningStatus(boolean warned, boolean shouldNotify,
                         BigDecimal failedCredits, int failedCourseCount,
                         BigDecimal threshold,
                         BigDecimal lastNotifiedCredits, LocalDateTime lastReadAt,
                         List<FailedCourse> courses) {
    }

    /** 计算某生的学业预警状态 */
    WarningStatus statusFor(String studentId);

    /**
     * 学生确认（标记已读）：把**当前**的未通过学分记为新水位线。
     *
     * <p>未达到预警条件时**不记录**（没有可确认的内容）；同一水位线重复确认是**幂等**的
     * （唯一索引 {@code uk_warning_student_credits} 兜底），不会重复写入。
     *
     * @return 本次是否真的写入了新水位线
     */
    boolean markRead(String studentId);
}
