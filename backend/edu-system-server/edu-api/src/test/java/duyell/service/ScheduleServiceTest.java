package duyell.service;

import com.duyell.Room;
import duyell.mapper.CourseMapper;
import duyell.mapper.RoomMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排课冲突检测与教室推荐测试（真实 MySQL）。
 *
 * <p><b>为什么是集成测试而不是纯单测</b>：冲突判据本身写在 SQL 里
 * （{@code ClassTimeMapper.xml#selectConflicts} 与 {@code RoomMapper.xml#pickFreeRooms}），
 * 把 Mapper stub 掉的"单测"只能验证 Java 分支，**验证不了重叠判据**——
 * 而重叠判据恰好是这块唯一容易写错的地方（`&lt;=` 写成 `&lt;` 就把"相接"误判成冲突）。
 * 所以这里直接打真库。
 *
 * <p><b>只读</b>：全部断言建立在迁移脚本的课表种子上，不写库、不造临时数据，
 * 因此可重复执行，也不会污染演示数据。种子
 * （docs/sql/2026-09-20-p2-schedule-migration.sql）：
 * <pre>
 * CS101  周一 1-2 节 第 1-16 周 教1-101  教师 10001  学期 2024-2025-1
 * CS102  周三 3-4 节 第 1-16 周 教1-102  教师 10002  学期 2024-2025-1
 * CS105  周二 3-5 节 第 9-16 周 教1-201  教师 10003  学期 2024-2025-2
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleServiceTest {

    private static final String TERM_1 = "2024-2025-1";
    private static final String TERM_2 = "2024-2025-2";
    private static final String TEACHER_CS101 = "10001";
    private static final String TEACHER_CS102 = "10002";
    private static final String TEACHER_CS105 = "10003";

    @Autowired
    private ScheduleService scheduleService;

    /** 仅用于按名字/代码把种子数据解析成 id，不参与被测逻辑 */
    @Autowired
    private RoomMapper roomMapper;

    @Autowired
    private CourseMapper courseMapper;

    // ==================== 节次区间重叠：闭区间 ====================

    /**
     * 用户明确的关键边界：**相接不算冲突**。
     * CS101 占周一 1-2 节；候选 3-4 节紧邻其后，不重叠 → 不冲突。
     */
    @Test
    void adjacentPeriodsDoNotConflict() {
        ScheduleService.ConflictResult r = check(TEACHER_CS101, null, 1, 3, 4, 1, 16);

        assertFalse(r.conflict(), "1-2 节与 3-4 节是相邻的两节课，不应判为冲突：" + r.describe());
    }

    /**
     * 同样重要：**端点上重叠算冲突**。
     * CS102 占周三 3-4 节；候选 4-5 节与它共用第 4 节 → 冲突。
     */
    @Test
    void overlappingEndpointPeriodsConflict() {
        ScheduleService.ConflictResult r = check(TEACHER_CS102, null, 3, 4, 5, 1, 16);

        assertTrue(r.conflict(), "3-4 节与 4-5 节共用第 4 节，应判为冲突");
        assertEquals(1, r.teacherConflicts().size());
        assertEquals("CS102", r.teacherConflicts().get(0).courseCode());
    }

    @Test
    void identicalPeriodsConflict() {
        assertTrue(check(TEACHER_CS101, null, 1, 1, 2, 1, 16).conflict(), "完全相同的时段必然冲突");
    }

    /** 候选区间把既有课完全包住，也要检出 */
    @Test
    void enclosingRangeStillConflicts() {
        assertTrue(check(TEACHER_CS101, null, 1, 1, 5, 1, 16).conflict(), "更长的区间覆盖了 1-2 节，应冲突");
    }

    /** 单节候选（只上第 2 节）落在既有区间内 → 冲突 */
    @Test
    void singlePeriodInsideRangeConflicts() {
        assertTrue(check(TEACHER_CS101, null, 1, 2, 2, 1, 16).conflict(), "第 2 节落在 1-2 节内，应冲突");
    }

    // ==================== 周次区间 ====================

    /** 第 1-16 周的课，与第 17-18 周完全不相交 → 不冲突 */
    @Test
    void nonOverlappingWeeksDoNotConflict() {
        ScheduleService.ConflictResult r = check(TEACHER_CS101, null, 1, 1, 2, 17, 18);
        assertFalse(r.conflict(), "第 17-18 周与第 1-16 周不相交，不应冲突：" + r.describe());
    }

    /** 周次部分重叠即冲突（第 5-8 周落在第 1-16 周内） */
    @Test
    void partiallyOverlappingWeeksConflict() {
        assertTrue(check(TEACHER_CS101, null, 1, 1, 2, 5, 8).conflict(), "周次区间有交集，应冲突");
    }

    /** 周次端点相接：第 1-16 周与第 16-20 周共用第 16 周 → 冲突（闭区间） */
    @Test
    void touchingWeekEndpointsConflict() {
        assertTrue(check(TEACHER_CS101, null, 1, 1, 2, 16, 20).conflict(), "共用第 16 周，应冲突");
    }

    /**
     * 用户明确要求的**非整学期**：CS105 是第 9-16 周的课。
     * 第 1-8 周不冲突、第 9-16 周冲突——证明周次真的按区间比，而不是只比开头。
     */
    @Test
    void midSemesterCourseOnlyConflictsInItsOwnWeeks() {
        ScheduleService.ConflictResult before = check(TEACHER_CS105, null, 2, 3, 5, 1, 8, TERM_2);
        assertFalse(before.conflict(), "CS105 从第 9 周才开始，第 1-8 周不应冲突：" + before.describe());

        ScheduleService.ConflictResult during = check(TEACHER_CS105, null, 2, 3, 5, 9, 16, TERM_2);
        assertTrue(during.conflict(), "第 9-16 周与 CS105 完全重合，应冲突");
    }

    // ==================== 学期与星期的作用域 ====================

    /** 同一教师、同一时段，但**不同学期** → 不冲突（CS101 在 2024-2025-1） */
    @Test
    void sameSlotInAnotherTermDoesNotConflict() {
        ScheduleService.ConflictResult r = check(TEACHER_CS101, null, 1, 1, 2, 1, 16, TERM_2);
        assertFalse(r.conflict(), "跨学期不应冲突：" + r.describe());
    }

    /** 同一教师、同一时段，但**不同星期** → 不冲突 */
    @Test
    void sameSlotOnAnotherWeekdayDoesNotConflict() {
        ScheduleService.ConflictResult r = check(TEACHER_CS101, null, 5, 1, 2, 1, 16);
        assertFalse(r.conflict(), "不同星期不应冲突：" + r.describe());
    }

    /** 同一时段、同一学期，但**不同教师** → 教师维度不冲突 */
    @Test
    void anotherTeacherIsNotATeacherConflict() {
        ScheduleService.ConflictResult r = check(TEACHER_CS102, null, 1, 1, 2, 1, 16);
        assertFalse(r.conflict(), "教师 10002 周一 1-2 节没课，不应冲突：" + r.describe());
    }

    // ==================== 教室维度 ====================

    /** 教室维度独立于教师：别人占了这个教室，一样是冲突 */
    @Test
    void occupiedRoomConflictsEvenForAnotherTeacher() {
        ScheduleService.ConflictResult r = scheduleService.checkConflict(null, TERM_1, TEACHER_CS102,
                roomId("教1-101"), 1, 1, 2, 1, 16);

        assertTrue(r.conflict(), "教1-101 在周一 1-2 节已被 CS101 占用，应检出教室冲突");
        assertTrue(r.teacherConflicts().isEmpty(), "教师冲突应为空（10002 此时段无课）");
        assertEquals(1, r.roomConflicts().size());
        assertEquals("CS101", r.roomConflicts().get(0).courseCode());
    }

    /** 教室在该时段空闲（相邻节次）→ 不冲突 */
    @Test
    void freeRoomAtAdjacentPeriodsDoesNotConflict() {
        ScheduleService.ConflictResult r = scheduleService.checkConflict(null, TERM_1, TEACHER_CS102,
                roomId("教1-101"), 1, 3, 4, 1, 16);
        assertFalse(r.conflict(), "教1-101 只在 1-2 节被占，3-4 节应空闲：" + r.describe());
    }

    /** 不传教室就只查教师维度，不会因为"没教室"而无脑报冲突 */
    @Test
    void nullRoomSkipsRoomConflicts() {
        assertTrue(check(TEACHER_CS102, null, 1, 1, 2, 1, 16).roomConflicts().isEmpty());
    }

    /**
     * excludeCourseId 的语义：排除自己后不再检出。
     *
     * <p>注意审批**新增**排课时必须传 null：同一门课在同一时间再排一次本身就是冲突。
     */
    @Test
    void excludeCourseIdRemovesOwnConflict() {
        Integer cs101 = courseMapper.selectIdByCode("CS101");
        assertNotNull(cs101, "需要 P1 迁移回填的 CS101 课程代码");

        assertTrue(check(TEACHER_CS101, null, 1, 1, 2, 1, 16).conflict(),
                "不排除时，CS101 与自己同时段当然冲突");

        ScheduleService.ConflictResult with = scheduleService.checkConflict(cs101, TERM_1, TEACHER_CS101, null,
                1, 1, 2, 1, 16);
        assertFalse(with.conflict(), "排除 CS101 本身后不应再检出：" + with.describe());
    }

    // ==================== 冲突详情的可读性 ====================

    /** 冲突描述要能告诉用户"和谁撞了"，而不是只回一个 true */
    @Test
    void conflictDescriptionNamesTheCourseAndSlot() {
        String text = check(TEACHER_CS101, null, 1, 1, 2, 1, 16).describe();

        assertTrue(text.contains("CS101"), "描述里应有课程代码：" + text);
        assertTrue(text.contains("教师"), "描述里应说明是教师冲突：" + text);
    }

    // ==================== 入参校验 ====================

    @Test
    void rejectsIllegalSlots() {
        // 节次越界（一天 10 节）
        assertThrows(BusinessException.class, () -> check(TEACHER_CS101, null, 1, 1, 11, 1, 16));
        // 星期越界
        assertThrows(BusinessException.class, () -> check(TEACHER_CS101, null, 8, 1, 2, 1, 16));
        // 起始节大于结束节
        assertThrows(BusinessException.class, () -> check(TEACHER_CS101, null, 1, 5, 2, 1, 16));
        // 起始周大于结束周
        assertThrows(BusinessException.class, () -> check(TEACHER_CS101, null, 1, 1, 2, 10, 3));
        // 周次为 0
        assertThrows(BusinessException.class, () -> check(TEACHER_CS101, null, 1, 1, 2, 0, 16));
        // 学期为空
        assertThrows(BusinessException.class, () -> scheduleService.checkConflict(null, "  ", TEACHER_CS101, null, 1, 1, 2, 1, 16));
    }

    @Test
    void slotValidationAcceptsLegalValues() {
        scheduleService.validateSlot(1, 1, 2, 1, 16);
        scheduleService.validateSlot(7, 3, 5, 9, 16);
        scheduleService.validateSlot(1, 10, 10, 1, 1);
    }

    // ==================== 教室推荐 ====================

    /**
     * 推荐结果**不含被占用的教室**，且容量最小者优先。
     *
     * <p>可稳定断言的前提：排序按「容量、楼栋、楼层、房间号」而不是自增 id，
     * 因此"教1-101 被占 → 下一个是教1-102"在任何库上都成立。
     */
    @Test
    void recommendationSkipsTheOccupiedRoom() {
        // 周一 1-2 节：教1-101 被 CS101 占用 → 首推应为同层下一间
        ScheduleService.RoomRecommendation busy = scheduleService.recommendRoom(
                TERM_1, 1, 1, 2, 1, 16, 60, null, 1);
        assertTrue(busy.found(), busy.message());
        assertEquals("教1-102", busy.roomName(), "教1-101 已被占用，应推荐容量同为 60 的下一间");

        // 周一 3-4 节：同一间教室空闲 → 首推就是它
        ScheduleService.RoomRecommendation free = scheduleService.recommendRoom(
                TERM_1, 1, 3, 4, 1, 16, 60, null, 1);
        assertTrue(free.found(), free.message());
        assertEquals("教1-101", free.roomName(), "该时段教1-101 空闲，且容量最小，应首推");
    }

    /** 候选按容量升序，且都满足容量下限 */
    @Test
    void candidatesAreOrderedByCapacityAndRespectTheFloor() {
        ScheduleService.RoomRecommendation rec = scheduleService.recommendRoom(
                TERM_1, 1, 3, 4, 1, 16, 100, null, 10);

        assertTrue(rec.found(), rec.message());
        assertEquals(10, rec.candidates().size(), "limit 生效");
        int previous = 0;
        for (Room room : rec.candidates()) {
            assertTrue(room.getCapacity() >= 100,
                    "容量不得低于下限：" + room.getRoomName() + " " + room.getCapacity());
            assertTrue(room.getCapacity() >= previous, "应按容量升序返回");
            previous = room.getCapacity();
        }
    }

    /** 被占教室是容量 80 的，不该影响容量 60 的首选 */
    @Test
    void occupancyOnlyAffectsTheSameTimeSlot() {
        ScheduleService.RoomRecommendation rec = scheduleService.recommendRoom(
                TERM_2, 2, 3, 5, 9, 16, 60, null, 1);
        assertTrue(rec.found(), rec.message());
        assertEquals(60, rec.candidates().get(0).getCapacity());
    }

    /** 容量要求超过所有教室 → 明确告诉用户"没这么大的教室"（与"都被占"区分开） */
    @Test
    void reportsWhenNoRoomIsBigEnough() {
        ScheduleService.RoomRecommendation rec = scheduleService.recommendRoom(
                TERM_1, 1, 1, 2, 1, 16, 999, null, 5);

        assertFalse(rec.found(), "没有容量 999 的教室");
        assertTrue(rec.message().contains("没有容量不小于"), "应说明是没有足够大的教室：" + rec.message());
    }

    /** 教室推荐也要挡住非法时段（与冲突检测同一套校验） */
    @Test
    void recommendationRejectsIllegalSlot() {
        assertThrows(BusinessException.class, () -> scheduleService.recommendRoom(
                TERM_1, 1, 1, 2, 16, 1, 60, null, 5));
    }

    // ==================== 按课程的入口 ====================

    /** 按课程查冲突：学期与教师自动从课程推导，避免调用方自己拼错导致漏检 */
    @Test
    void conflictCheckByCourseResolvesTermAndTeacher() {
        ScheduleService.ConflictResult r = scheduleService.checkConflictForCourse(
                courseMapper.selectIdByCode("CS101"), null, 1, 1, 2, 1, 16);
        assertTrue(r.conflict(), "CS101 自己占着周一 1-2 节，按课程查也必须检出");
    }

    @Test
    void conflictCheckByCourseRejectsUnknownCourse() {
        assertThrows(BusinessException.class, () -> scheduleService.checkConflictForCourse(
                999999, null, 1, 1, 2, 1, 16));
    }

    // ==================== 辅助 ====================

    /** 默认学期 TERM_1 的冲突检测 */
    private ScheduleService.ConflictResult check(String teacherId, Integer roomId,
                                                 int weekday, int startPeriod, int endPeriod,
                                                 int startWeek, int endWeek) {
        return check(teacherId, roomId, weekday, startPeriod, endPeriod, startWeek, endWeek, TERM_1);
    }

    private ScheduleService.ConflictResult check(String teacherId, Integer roomId,
                                                 int weekday, int startPeriod, int endPeriod,
                                                 int startWeek, int endWeek, String term) {
        return scheduleService.checkConflict(null, term, teacherId, roomId,
                weekday, startPeriod, endPeriod, startWeek, endWeek);
    }

    /** 按展示名取教室 id（不按自增 id，保证在任何库上都稳定） */
    private Integer roomId(String roomName) {
        Room room = roomMapper.selectByName(roomName);
        assertNotNull(room, "找不到教室 " + roomName + "，请先执行 P2 迁移脚本");
        return room.getId();
    }
}
