package duyell.service;

import com.duyell.Course;
import com.duyell.Score;
import duyell.mapper.CourseMapper;
import duyell.mapper.ScoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学业预警测试（真实 MySQL）。
 *
 * <p>制度依据：{@code docs/policies/01-学籍管理规定.md} §5（v1.2，作者 2026-09-20 审计确定）：
 * 只做预警提示、不做留级/退学自动化；口径为"**未通过课程学分累计 ≥ 阈值**"（默认 8 学分）；
 * 通知方式是**只弹一次、学生可标记已读**。
 *
 * <p>本测试写真实成绩表，用默认回滚事务隔离（与审计测试刻意相反：那个必须提交才能验证）。
 * 因此每个用例都可以反复跑而不污染数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AcademicWarningServiceTest {

    @Autowired
    private AcademicWarningService warningService;

    @Autowired
    private ScoreMapper scoreMapper;

    @Autowired
    private CourseMapper courseMapper;

    /** 用一个"成绩干净"的测试学生：2024002 在库中只有 1 门已通过课程（course 2） */
    private static final String STUDENT = "2024002";

    /** 库中 2024002 没有成绩的两门课（各 4.0 / 3.0 学分），及其授课教师 */
    private static final int COURSE_CS101 = 1;   // 4.0 学分
    private static final int COURSE_CS102 = 2;   // 3.0 学分（2024002 已通过，用于"已通过不计入"）
    private static final int COURSE_CS107 = 10;  // 4.0 学分
    private static final int COURSE_CS104 = 4;   // 3.5 学分（"变严重"用例里再挂一门）

    /**
     * 每个用例先把本测试要用的几门课清干净。
     *
     * <p>为什么要这么做：浏览器层脚本（`.dsh/verify-warning-ui.cjs`）会用**同一个学生**造不及格成绩，
     * 一旦它中途被杀（本机真的发生过：DSH 宿主崩溃会带走后台任务），夹具行就残留在库里，
     * 本测试再插入同一 (课程, 学生) 就会撞唯一键 `uk_score_course_student` 而红——
     * 报错长得像业务缺陷，实际只是夹具不干净（本仓库"种子数据/夹具"类坑的又一实例）。
     *
     * <p>清理发生在测试事务内、测试结束回滚，因此**不会破坏真实数据**，
     * 同时也让本类不再依赖"库里恰好没有这些行"这个外部假设。
     */
    @BeforeEach
    void clearFixture() {
        for (int courseId : new int[]{COURSE_CS101, COURSE_CS107, COURSE_CS104}) {
            scoreMapper.delete(courseId, Integer.valueOf(STUDENT));
        }
    }

    private void addScore(int courseId, String usual, String exam) {
        Score s = new Score();
        s.setCourseId(courseId);
        s.setStudentId(STUDENT);
        s.setUsualScore(new BigDecimal(usual));
        s.setExamScore(new BigDecimal(exam));
        s.setTotalScore(new BigDecimal(usual).multiply(new BigDecimal("0.4"))
                .add(new BigDecimal(exam).multiply(new BigDecimal("0.6"))));
        s.setPassed(s.getTotalScore().compareTo(new BigDecimal("60")) >= 0 ? 1 : 0);
        scoreMapper.add(s);
    }

    @Test
    void noScoresMeansNoWarning() {
        AcademicWarningService.WarningStatus status = warningService.statusFor(STUDENT);

        assertFalse(status.warned(), "没有未通过课程时不应预警");
        assertEquals(0, status.failedCredits().compareTo(BigDecimal.ZERO));
        assertEquals(0, status.failedCourseCount());
        assertFalse(status.shouldNotify(), "未达条件不应弹通知");
        assertNull(status.lastNotifiedCredits(), "从未确认过水位线应为 null");
    }

    /** 口径核心：只统计**未通过**课程的学分，已通过的课程不计入 */
    @Test
    void passedCourseIsNotCounted() {
        // course 2 是 2024002 已通过的课（种子数据 86.8 分）
        AcademicWarningService.WarningStatus before = warningService.statusFor(STUDENT);
        assertFalse(before.courses().stream().anyMatch(c -> c.courseId() == COURSE_CS102),
                "已通过的课程不应出现在未通过清单里");

        addScore(COURSE_CS101, "40", "45"); // 42 分，未通过，4.0 学分
        AcademicWarningService.WarningStatus after = warningService.statusFor(STUDENT);
        assertTrue(after.courses().stream().anyMatch(c -> c.courseId() == COURSE_CS101));
        assertEquals(0, after.failedCredits().compareTo(new BigDecimal("4.0")));
    }

    /** 阈值边界：**恰好等于**阈值也算触发（与"达到阈值"的措辞一致） */
    @Test
    void warningTriggersExactlyAtThreshold() {
        addScore(COURSE_CS101, "40", "45");  // 未通过 4.0 学分
        addScore(COURSE_CS107, "30", "30");  // 未通过 4.0 学分 → 合计 8.0 = 阈值

        AcademicWarningService.WarningStatus status = warningService.statusFor(STUDENT);

        assertEquals(0, status.failedCredits().compareTo(new BigDecimal("8.0")));
        assertEquals(2, status.failedCourseCount());
        assertEquals(0, status.threshold().compareTo(new BigDecimal("8")));
        assertTrue(status.warned(), "恰好达到阈值应触发预警");
    }

    /** 差一点不触发：7.x < 8 */
    @Test
    void warningDoesNotTriggerBelowThreshold() {
        addScore(COURSE_CS101, "40", "45");  // 4.0 学分（未通过）

        AcademicWarningService.WarningStatus status = warningService.statusFor(STUDENT);

        assertTrue(status.failedCredits().compareTo(new BigDecimal("8")) < 0);
        assertFalse(status.warned(), "未达阈值不应触发");
    }

    /** 只弹一次：确认（标记已读）后不再提示；情况变严重才再次提示 */
    @Test
    void notifyOnlyOnceUntilItGetsWorse() {
        addScore(COURSE_CS101, "40", "45");
        addScore(COURSE_CS107, "30", "30"); // 8.0 学分，触发

        AcademicWarningService.WarningStatus first = warningService.statusFor(STUDENT);
        assertTrue(first.shouldNotify(), "首次触发应提示");

        assertTrue(warningService.markRead(STUDENT), "第一次确认应写入水位线");

        AcademicWarningService.WarningStatus afterRead = warningService.statusFor(STUDENT);
        assertTrue(afterRead.warned(), "确认后仍然处于预警状态（只是不再重复提示）");
        assertFalse(afterRead.shouldNotify(), "已确认且情况未恶化 → 不再提示");
        assertNotNull(afterRead.lastReadAt());
        assertEquals(0, afterRead.lastNotifiedCredits().compareTo(new BigDecimal("8.0")));

        // 情况变严重（又挂一门 3.5 学分的课）→ 再提示一次
        addScore(4, "20", "20"); // course 4 = 数据库原理 3.5 学分，2024002 无成绩
        AcademicWarningService.WarningStatus worse = warningService.statusFor(STUDENT);
        assertTrue(worse.failedCredits().compareTo(new BigDecimal("8.0")) > 0,
                "应累计到 11.5 学分，实际 " + worse.failedCredits());
        assertTrue(worse.shouldNotify(), "比上次确认时更严重 → 应再次提示");
    }

    /** 标记已读是幂等的：同一水位线重复确认不重复写入、不报错 */
    @Test
    void markReadIsIdempotent() {
        addScore(COURSE_CS101, "40", "45");
        addScore(COURSE_CS107, "30", "30");

        assertTrue(warningService.markRead(STUDENT), "第一次确认应写入");
        assertFalse(warningService.markRead(STUDENT), "同一水位线重复确认应被忽略（幂等）");
        assertFalse(warningService.markRead(STUDENT));
    }

    /** 未达预警条件时"确认"不写水位线（否则会把未预警也记成水位线，掩盖以后的首次预警） */
    @Test
    void markReadWithoutWarningRecordsNothing() {
        AcademicWarningService.WarningStatus status = warningService.statusFor(STUDENT);

        assertFalse(status.warned());
        assertFalse(warningService.markRead(STUDENT), "未预警时不应写入水位线");
        assertNull(warningService.statusFor(STUDENT).lastNotifiedCredits());
    }

    /**
     * 同一课程代码多条成绩（重修）：只要有**一条通过**，该课程就**不算未通过**，学分也不计入。
     *
     * <p>构造方式：库中每个课程代码只有一门课，但 {@code course_code} **刻意没有唯一约束**
     * （同一门课不同学期共用同一代码，见设计文档 §3.1），所以这里插一条同代码的临时课程来制造
     * "两条不同 course_id、同一 course_code"的真实场景。
     */
    @Test
    void retakeWithOnePassIsNotFailed() {
        // 第一条：CS101（course 1）未通过
        addScore(COURSE_CS101, "30", "30");
        assertTrue(warningService.statusFor(STUDENT).courses().stream()
                        .anyMatch(c -> "CS101".equals(c.courseCode())),
                "只有一次未通过记录时，CS101 应计入未通过");

        // 第二条：同代码的另一门开课记录（模拟重修），本次通过
        Course retake = new Course();
        retake.setCourseCode("CS101");
        retake.setCourseName("Java程序设计");
        retake.setTeacherId("10001");
        retake.setCollegeId(1);
        retake.setTerm("2099-2099-1");
        retake.setCredit(new BigDecimal("4.0"));
        retake.setClassHour(64);
        retake.setMaxStudent(50);
        courseMapper.add(retake);
        assertNotNull(retake.getId(), "临时课程应回填自增 id");
        addScore(retake.getId(), "88", "90"); // 重修通过

        AcademicWarningService.WarningStatus status = warningService.statusFor(STUDENT);

        assertTrue(status.courses().stream().noneMatch(c -> "CS101".equals(c.courseCode())),
                "同代码只要有一条通过，就不应算作未通过；实际清单=" + status.courses());
        assertEquals(0, status.failedCredits().compareTo(BigDecimal.ZERO),
                "该课程学分不应计入未通过学分累计");
    }
}
