package duyell.service;

import com.duyell.Course;
import com.duyell.SelectionRound;
import com.duyell.SelectionRoundScope;
import duyell.mapper.CourseSelectionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 选课轮次与选课校验链测试（真实 MySQL）。
 *
 * <p>依赖迁移脚本 {@code docs/sql/2026-09-20-p3-selection-round-migration.sql} 的三个种子轮次，
 * 分别覆盖三种状态（时间窗用 NOW() 相对计算，所以不会过期）：
 * <pre>
 * 轮次1  2024-2025-1  开启中，选课窗口内                 → 可选可退
 * 轮次2  2024-2025-2  开启中，选课窗口已过、补退选窗口内  → 只能退
 * 轮次3  2025-2026-1  未开启（status=0），窗口有效       → 只能看
 * </pre>
 *
 * <p><b>为什么校验链 4/5/6 道不在这里测</b>：种子数据里不存在
 * "已通过但没选过"的课程、也不存在互相冲突的两门已选课、更没有满员课程，
 * 硬测就得造假数据。这三道放在 {@code .dsh/verify-p3-api.ps1} 里，
 * 用真实 HTTP + 可清理的 fixture 覆盖（那里的清理机制已经过 P2 验证）。
 * 这里负责的是：三个状态的判定与原因、范围匹配、以及**不写库**就能验证的部分。
 *
 * <p>唯一写库的用例是 {@code selectAndDropRoundTrip}（选 MA101 再退掉），
 * 用 {@code @AfterEach} 兜底清理，且 MA101 本来就不在种子选课里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SelectionRoundAndSelectionServiceTest {

    private static final String TERM_OPEN = "2024-2025-1";
    private static final String TERM_DROP_ONLY = "2024-2025-2";
    private static final String TERM_CLOSED = "2025-2026-1";
    private static final String STUDENT = "2023001";
    /** 2023001 在 2024-2025-1 唯一没选、也没通过的课（数学分析，5 学分，40 人容量，无排课） */
    private static final int COURSE_MA101 = 7;

    @Autowired
    private SelectionRoundService selectionRoundService;

    @Autowired
    private CourseSelectionService courseSelectionService;

    @Autowired
    private CourseSelectionMapper courseSelectionMapper;

    /** 兜底清理：断言失败时也不会留下选课痕迹（删不存在的行是空操作） */
    @AfterEach
    void cleanup() {
        courseSelectionMapper.delete(COURSE_MA101, STUDENT);
    }

    /**
     * "是否只读"必须由调用方推导：{@code SelectionStatus} 是 record，
     * 派生方法不会被 Jackson 序列化，所以接口报文里没有 readOnly 字段（P1 已踩过同样的坑）。
     */
    private static boolean readOnly(SelectionRoundService.SelectionStatus status) {
        return !status.canSelect() && !status.canDrop();
    }

    // ==================== 三种轮次状态 ====================

    @Test
    void openRoundAllowsBothSelectingAndDropping() {
        SelectionRoundService.SelectionStatus status = selectionRoundService.statusFor(STUDENT, TERM_OPEN);

        assertTrue(status.roundOpen(), "2024-2025-1 的轮次应处于开启状态");
        assertTrue(status.canSelect(), "选课窗口内应可以选课");
        assertTrue(status.canDrop(), "选课窗口内也应可以退课（用户明确：选课期间可以退）");
        assertNotNull(status.roundId(), "应回传命中的轮次 id（选课要记进 round_id）");
        assertNotNull(status.maxCredits(), "种子轮次配了 30 学分上限");
        assertEquals(0, status.maxCredits().compareTo(new java.math.BigDecimal("30.0")));
        assertFalse(readOnly(status));
    }

    /** 用户明确："否则要等补退选期间才能退" —— 补退选窗口内只退不选 */
    @Test
    void dropOnlyRoundRefusesSelectingButAllowsDropping() {
        SelectionRoundService.SelectionStatus status = selectionRoundService.statusFor(STUDENT, TERM_DROP_ONLY);

        assertTrue(status.roundOpen(), "轮次本身是开启的");
        assertFalse(status.canSelect(), "选课窗口已过，不应还能选");
        assertTrue(status.canDrop(), "补退选窗口内应可以退课");
        assertNotNull(status.reason());
        assertTrue(status.reason().contains("补退选"), "原因应说明当前是补退选期间：" + status.reason());
    }

    /** 用户明确："只有管理员开启选课后学生才能选；否则只能看" */
    @Test
    void closedRoundIsReadOnlyEvenInsideTheTimeWindow() {
        SelectionRoundService.SelectionStatus status = selectionRoundService.statusFor(STUDENT, TERM_CLOSED);

        assertFalse(status.roundOpen(), "status=0 的轮次不算开放");
        assertFalse(status.canSelect(), "未开启时不能选课");
        assertFalse(status.canDrop(), "未开启时也不能退课");
        assertTrue(readOnly(status));
        assertTrue(status.reason().contains("没有已开启"), "原因应说明轮次未开启：" + status.reason());
    }

    @Test
    void termWithoutAnyRoundIsReadOnly() {
        SelectionRoundService.SelectionStatus status = selectionRoundService.statusFor(STUDENT, "2030-2031");

        assertFalse(status.roundOpen());
        assertTrue(readOnly(status));
        assertTrue(status.reason().contains("没有已开启"), status.reason());
    }

    @Test
    void blankTermAndUnknownStudentAreHandledWithoutThrowing() {
        SelectionRoundService.SelectionStatus blank = selectionRoundService.statusFor(STUDENT, "  ");
        assertFalse(blank.canSelect());
        assertTrue(blank.reason().contains("未指定学期"), blank.reason());

        SelectionRoundService.SelectionStatus unknown = selectionRoundService.statusFor("9999999", TERM_OPEN);
        assertFalse(unknown.canSelect());
        assertTrue(unknown.reason().contains("学生不存在"), unknown.reason());
    }

    // ==================== requireCan* 的语义 ====================

    @Test
    void requireCanSelectReturnsTheHitRound() {
        SelectionRound round = selectionRoundService.requireCanSelect(STUDENT, TERM_OPEN);

        assertNotNull(round);
        assertEquals(TERM_OPEN, round.getTerm());
        assertEquals(SelectionRound.STATUS_OPEN, round.getStatus());
    }

    @Test
    void requireCanSelectFailsWhenOnlyTheDropWindowIsOpen() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> selectionRoundService.requireCanSelect(STUDENT, TERM_DROP_ONLY));
        assertTrue(e.getMessage().contains("补退选"), e.getMessage());
    }

    @Test
    void requireCanDropFailsWhenTheRoundIsClosed() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> selectionRoundService.requireCanDrop(STUDENT, TERM_CLOSED));
        assertTrue(e.getMessage().contains("没有已开启"), e.getMessage());
    }

    // ==================== 适用范围匹配（纯逻辑） ====================

    @Test
    void scopeMatchesOnlyTheConditionsThatAreFilled() {
        // 全空 = 不限 → 任何人都匹配
        SelectionRoundScope unrestricted = new SelectionRoundScope(1, 1, null, null, null, null, null);
        assertTrue(unrestricted.isUnrestricted());
        assertTrue(unrestricted.matches("2023", 1, 1));
        assertTrue(unrestricted.matches(null, null, null));

        // 限定年级
        SelectionRoundScope byGrade = new SelectionRoundScope(2, 1, "2023", null, null, null, null);
        assertFalse(byGrade.isUnrestricted());
        assertTrue(byGrade.matches("2023", 5, 9));
        assertFalse(byGrade.matches("2024", 5, 9));

        // 限定专业
        SelectionRoundScope byMajor = new SelectionRoundScope(3, 1, null, 2, null, null, null);
        assertTrue(byMajor.matches("2023", 2, 9));
        assertFalse(byMajor.matches("2023", 1, 9));

        // 限定学院
        SelectionRoundScope byCollege = new SelectionRoundScope(4, 1, null, null, 3, null, null);
        assertTrue(byCollege.matches("2023", 1, 3));
        assertFalse(byCollege.matches("2023", 1, 4));
    }

    /** 多个条件是与关系：填了的都必须相等 */
    @Test
    void scopeConditionsAreCombinedWithAnd() {
        SelectionRoundScope scope = new SelectionRoundScope(5, 1, "2023", 1, 1, null, null);

        assertTrue(scope.matches("2023", 1, 1));
        assertFalse(scope.matches("2023", 1, 2), "学院不符应不匹配");
        assertFalse(scope.matches("2023", 2, 1), "专业不符应不匹配");
        assertFalse(scope.matches("2024", 1, 1), "年级不符应不匹配");
    }

    /**
     * 学生缺少专业/年级信息时，范围里填了的条件一律不匹配——
     * 宁可少放行，也不要把范围校验变成摆设。
     */
    @Test
    void missingStudentPlacementDoesNotSatisfyAFilledCondition() {
        SelectionRoundScope byGrade = new SelectionRoundScope(6, 1, "2023", null, null, null, null);
        assertFalse(byGrade.matches(null, null, null));
    }

    // ==================== 可选课程列表（校验链的结果，不写库） ====================

    @Test
    void selectableListMarksAlreadySelectedCourses() {
        List<CourseSelectionService.SelectableCourse> list =
                courseSelectionService.listSelectableCourses(STUDENT, TERM_OPEN);

        assertEquals(7, list.size(), "2024-2025-1 有 7 门课");
        long selected = list.stream().filter(CourseSelectionService.SelectableCourse::selected).count();
        assertEquals(6, selected, "2023001 已选 6 门");

        CourseSelectionService.SelectableCourse ma101 = list.stream()
                .filter(c -> COURSE_MA101 == c.course().getId())
                .findFirst().orElseThrow();
        assertFalse(ma101.selected(), "MA101 未选");
        assertTrue(ma101.selectable(), "MA101 六道校验应全部通过，因此可选：" + ma101.reason());
    }

    /** 补退选期间，"能看不能选"要逐门给出原因 */
    @Test
    void selectableListExplainsWhyNothingCanBeSelected() {
        List<CourseSelectionService.SelectableCourse> list =
                courseSelectionService.listSelectableCourses(STUDENT, TERM_DROP_ONLY);

        assertFalse(list.isEmpty(), "2024-2025-2 有课");
        for (CourseSelectionService.SelectableCourse row : list) {
            assertFalse(row.selectable(), row.course().getCourseCode() + " 不应可选");
            assertNotNull(row.reason(), "不可选必须给出原因");
            assertTrue(row.reason().contains("补退选"), row.reason());
        }
    }

    // ==================== 选课 / 退课 ====================

    /**
     * 唯一写库的用例：选 MA101（该学期唯一满足六道校验的课）再退掉。
     * 同时验证了退课窗口（选课期间可以退）与"退课后记录消失"。
     */
    @Test
    void selectAndDropRoundTrip() {
        courseSelectionService.select(COURSE_MA101, STUDENT);
        assertNotNull(courseSelectionMapper.select(COURSE_MA101, STUDENT), "选课后应有记录");
        assertTrue(courseSelectionService.listMyCourseIds(STUDENT).contains(COURSE_MA101));

        SelectionRound hit = selectionRoundService.requireCanSelect(STUDENT, TERM_OPEN);
        assertEquals(hit.getId(),
                courseSelectionMapper.select(COURSE_MA101, STUDENT).getRoundId(),
                "选课记录应带上命中的轮次 id，便于追溯");

        courseSelectionService.drop(COURSE_MA101, STUDENT);
        assertEquals(null, courseSelectionMapper.select(COURSE_MA101, STUDENT), "退课后记录应消失");
    }

    @Test
    void selectingTheSameCourseTwiceIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> courseSelectionService.select(1, STUDENT));
        assertTrue(e.getMessage().contains("已选过"), e.getMessage());
    }

    @Test
    void droppingACourseThatWasNeverSelectedIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> courseSelectionService.drop(COURSE_MA101, STUDENT));
        assertTrue(e.getMessage().contains("还没有选"), e.getMessage());
    }

    @Test
    void selectingAnUnknownCourseIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> courseSelectionService.select(999999, STUDENT));
        assertTrue(e.getMessage().contains("课程不存在"), e.getMessage());
    }

    // ==================== 轮次管理（管理员） ====================

    @Test
    void listReturnsScopesForEachRound() {
        List<SelectionRound> rounds = selectionRoundService.list(null, null);

        assertTrue(rounds.size() >= 3, "至少有三个种子轮次");
        for (SelectionRound round : rounds) {
            assertNotNull(round.getScopes(), "列表应装配适用范围（避免前端逐行再查）");
        }
        SelectionRound closed = rounds.stream()
                .filter(r -> TERM_CLOSED.equals(r.getTerm()))
                .findFirst().orElseThrow();
        assertEquals(1, closed.getScopes().size(), "未开启那条配了一个范围示例（年级 2023）");
        assertEquals("2023", closed.getScopes().get(0).getGrade());
    }

    @Test
    void statusCanBeToggledAndIsReflectedInStatusFor() {
        SelectionRound closed = selectionRoundService.list(TERM_CLOSED, null).get(0);
        assertFalse(selectionRoundService.statusFor(STUDENT, TERM_CLOSED).canSelect());

        selectionRoundService.setStatus(closed.getId(), SelectionRound.STATUS_OPEN);
        try {
            // 该轮次范围限定年级 2023，2023001 正好是 2023 级 → 开启后即可选
            SelectionRoundService.SelectionStatus status =
                    selectionRoundService.statusFor(STUDENT, TERM_CLOSED);
            assertTrue(status.canSelect(), "开启后且在范围内、时间窗有效 → 可以选课");
            assertFalse(readOnly(status));
        } finally {
            // 必须还原：这条轮次的"未开启"状态是其他用例（和接口校验脚本）依赖的种子
            selectionRoundService.setStatus(closed.getId(), SelectionRound.STATUS_CLOSED);
        }
        assertFalse(selectionRoundService.statusFor(STUDENT, TERM_CLOSED).canSelect(), "已还原为未开启");
    }

    @Test
    void creatingARoundWithInvertedWindowIsRejected() {
        SelectionRound bad = new SelectionRound();
        bad.setRoundName("VERIFY-P3-BAD");
        bad.setTerm("2024-2025-1");
        bad.setSelectStart(java.time.LocalDateTime.now());
        bad.setSelectEnd(java.time.LocalDateTime.now().minusDays(1));

        BusinessException e = assertThrows(BusinessException.class,
                () -> selectionRoundService.create(bad));
        assertTrue(e.getMessage().contains("不能晚于"), e.getMessage());
    }

    @Test
    void creatingARoundWithoutTimesIsRejected() {
        SelectionRound bad = new SelectionRound();
        bad.setRoundName("VERIFY-P3-NOTIME");
        bad.setTerm("2024-2025-1");

        BusinessException e = assertThrows(BusinessException.class,
                () -> selectionRoundService.create(bad));
        assertTrue(e.getMessage().contains("选课开放的开始与结束"), e.getMessage());
    }

    @Test
    void unrestrictedScopeIsRejectedBecauseItMeansNoRestriction() {
        SelectionRoundScope empty = new SelectionRoundScope();
        empty.setRoundId(999999);
        assertThrows(BusinessException.class, () -> selectionRoundService.addScope(empty));
    }

    @Test
    void unknownRoundIsReported() {
        assertThrows(BusinessException.class,
                () -> selectionRoundService.setStatus(999999, SelectionRound.STATUS_OPEN));
        assertEquals(null, selectionRoundService.get(999999));
    }
}
