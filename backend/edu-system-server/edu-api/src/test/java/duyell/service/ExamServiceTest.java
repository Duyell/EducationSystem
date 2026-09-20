package duyell.service;

import com.duyell.ExamSchedule;
import duyell.mapper.CourseMapper;
import duyell.mapper.ExamScheduleMapper;
import duyell.mapper.RoomMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考试安排测试（真实 MySQL）。
 *
 * <p>依赖迁移脚本 {@code docs/sql/2026-09-20-p4-exam-migration.sql} 的种子：
 * CS101/CS102/CS103 各有 1 场**未来**的期末，CS104 有 1 场**已过**的补考。
 * 时间用「今天 + N 天」相对计算，所以不会过期。
 *
 * <p><b>本类最重要的用例是"恰好相接不算冲突"</b>：考试是**连续时钟区间**，
 * 用半开区间判据；而 P2 的节次是**离散格子**，用闭区间。
 * 两者语义不同，混用会把连着考的两场误判成冲突（或反过来漏判）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExamServiceTest {

    private static final String STUDENT = "2023001";
    private static final String TERM = "2024-2025-1";

    @Autowired
    private ExamService examService;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private RoomMapper roomMapper;

    @Autowired
    private ExamScheduleMapper examMapper;

    /** 兜底清理：唯一写库的用例是给 CS105 排一场考试，断言失败也不会留下残渣 */
    @AfterEach
    void cleanup() {
        for (ExamSchedule e : examService.listByCourse(courseId("CS105"))) {
            if ("VERIFY-P4".equals(e.getRemark())) {
                examService.remove(e.getId());
            }
        }
    }

    // ==================== 查询与排序 ====================

    /** 学生的考试＝其已选课程的考试，且按考试时间升序（已考的排在最前） */
    @Test
    void studentSeesOwnExamsInTimeOrder() {
        List<ExamSchedule> mine = examService.listByStudent(STUDENT, null, false);

        assertEquals(4, mine.size(), "2023001 选了 CS101-CS104，四场考试都该看到");
        List<String> codes = mine.stream().map(ExamSchedule::getCourseCode).toList();
        assertEquals(List.of("CS104", "CS101", "CS102", "CS103"), codes,
                "应按考试时间升序：已过的补考在最前，其余按日期");
        for (int i = 1; i < mine.size(); i++) {
            assertFalse(mine.get(i).getExamTime().isBefore(mine.get(i - 1).getExamTime()),
                    "结果必须按 exam_time 非降序");
        }
    }

    /** "我下周有什么考试" —— 只看还没开考的 */
    @Test
    void upcomingFilterExcludesPastExams() {
        List<ExamSchedule> upcoming = examService.listByStudent(STUDENT, null, true);

        assertEquals(3, upcoming.size(), "已过的那场补考不该出现");
        for (ExamSchedule e : upcoming) {
            assertTrue(e.upcoming(), e.getCourseCode() + " 应还未开考");
            assertTrue(e.getExamTime().isAfter(LocalDateTime.now()));
        }
    }

    @Test
    void studentCanFilterByTerm() {
        List<ExamSchedule> mine = examService.listByStudent(STUDENT, TERM, false);
        assertEquals(4, mine.size(), "四门课都在 2024-2025-1");
        for (ExamSchedule e : mine) {
            assertEquals(TERM, e.getTerm());
        }
        assertTrue(examService.listByStudent(STUDENT, "2033-2034", false).isEmpty());
        assertTrue(examService.listByStudent("9999999", null, false).isEmpty(), "未知学生没有考试");
    }

    @Test
    void listsAndFiltersByCourseAndType() {
        int cs101 = courseId("CS101");

        assertEquals(1, examService.listByCourse(cs101).size());
        assertEquals(1, examService.list(null, cs101, "FINAL", null).size());
        assertTrue(examService.list(null, cs101, "MAKEUP", null).isEmpty(), "CS101 没有补考");

        assertEquals(3, examService.list(null, null, "FINAL", null).size());
        assertEquals(1, examService.list(null, null, "MAKEUP", null).size());
        assertEquals(4, examService.list(null, null, null, null).size());
        assertEquals(4, examService.list(TERM, null, null, ExamSchedule.STATUS_ACTIVE).size());
    }

    /** 展示字段靠联表带出（前端直接可用），类型中文名是故意序列化的 getter */
    @Test
    void listCarriesDisplayFields() {
        ExamSchedule e = examService.listByCourse(courseId("CS101")).get(0);

        assertNotNull(e.getCourseCode());
        assertNotNull(e.getCourseName());
        assertNotNull(e.getRoomName());
        assertNotNull(e.getTerm());
        assertEquals("期末", e.getTypeLabel());
        assertNotNull(e.endTime(), "结束时刻＝开始＋时长");
        assertEquals(e.getExamTime().plusMinutes(e.getDurationMinutes()), e.endTime());
    }

    @Test
    void unknownExamReturnsNull() {
        assertNull(examService.get(999999));
    }

    // ==================== 冲突判据：半开区间（关键边界） ====================

    /**
     * 用户原则"不允许冲突"在考试上的落地：
     * 同一考场、时间**真正重叠**才算冲突；**恰好相接不算**。
     */
    @Test
    void sameRoomOverlappingIntervalConflicts() {
        ExamSchedule seeded = examService.listByCourse(courseId("CS101")).get(0);
        LocalDateTime start = seeded.getExamTime();
        int roomId = seeded.getRoomId();
        int cs105 = courseId("CS105");

        // 完全重合
        ExamService.ConflictResult same = examService.checkConflict(
                cs105, start, 120, roomId, null);
        assertTrue(same.conflict(), "同一考场同一时段必须冲突");
        assertEquals(1, same.roomConflicts().size());

        // 覆盖前半段：09:30-10:30 与 09:00-11:00 重叠
        ExamService.ConflictResult partial = examService.checkConflict(
                cs105, start.plusMinutes(30), 60, roomId, null);
        assertTrue(partial.conflict(), "任何真正的重叠都要检出：" + partial.describe());

        // 差一分钟就相接：10:59-11:59 与 09:00-11:00 仍重叠 1 分钟
        ExamService.ConflictResult oneMinute = examService.checkConflict(
                cs105, seeded.endTime().minusMinutes(1), 60, roomId, null);
        assertTrue(oneMinute.conflict(), "结束前 1 分钟开始仍算重叠");
    }

    /**
     * ★ 核心边界：**恰好相接不算冲突**（半开区间）。
     *
     * <p>这正是与 P2 节次冲突的分水岭：那里的"第 3-4 节 vs 第 4-5 节"共用第 4 节，
     * 属闭区间、算冲突；而"前一场 11:00 结束 / 后一场 11:00 开始"只是首尾相接，
     * 属半开区间、**不算**冲突。
     */
    @Test
    void touchingIntervalsDoNotConflict() {
        ExamSchedule seeded = examService.listByCourse(courseId("CS101")).get(0);
        int roomId = seeded.getRoomId();
        int cs105 = courseId("CS105");

        // 候选从既有考试结束的那一刻开始
        ExamService.ConflictResult after = examService.checkConflict(
                cs105, seeded.endTime(), 60, roomId, null);
        assertFalse(after.conflict(), "前一场结束即后一场开始，不算冲突：" + after.describe());

        // 候选在既有考试开始的那一刻结束
        int durationToStart = (int) java.time.Duration.between(
                seeded.getExamTime().minusHours(2), seeded.getExamTime()).toMinutes();
        ExamService.ConflictResult before = examService.checkConflict(
                cs105, seeded.getExamTime().minusMinutes(durationToStart), durationToStart, roomId, null);
        assertFalse(before.conflict(), "候选恰好在前一场开始时结束，不算冲突：" + before.describe());
    }

    /** 换一个考场就没冲突了（同一时段） */
    @Test
    void sameTimeInAnotherRoomDoesNotConflict() {
        ExamSchedule seeded = examService.listByCourse(courseId("CS101")).get(0);
        int otherRoom = roomIdByName("教1-103");

        ExamService.ConflictResult r = examService.checkConflict(
                courseId("CS105"), seeded.getExamTime(), 120, otherRoom, null);
        assertTrue(r.roomConflicts().isEmpty(), "换考场后不该有考场冲突：" + r.describe());
    }

    /** 学生维度：两门课有共同考生且时间重叠 → 检出 */
    @Test
    void sharedStudentsWithOverlappingExamsConflict() {
        ExamSchedule cs101 = examService.listByCourse(courseId("CS101")).get(0);

        // CS102 的考生里也包含 2023001（两门课他都选了），把候选排在 CS101 的时段
        ExamService.ConflictResult r = examService.checkConflict(
                courseId("CS102"), cs101.getExamTime(), 120, null, null);

        assertTrue(r.conflict(), "有学生要同时考两门，必须检出：" + r.describe());
        assertFalse(r.studentConflicts().isEmpty());
        assertTrue(r.studentConflicts().stream().anyMatch(e -> "CS101".equals(e.getCourseCode())),
                "应指出是和 CS101 撞了：" + r.describe());
        assertTrue(r.roomConflicts().isEmpty(), "没给考场就不该有考场冲突");
    }

    /** 没给考场就只查学生维度；时间选在无人冲突的空档则完全无冲突 */
    @Test
    void farFutureSlotHasNoConflict() {
        ExamService.ConflictResult r = examService.checkConflict(
                courseId("CS105"), LocalDateTime.now().plusDays(200).withHour(9).withMinute(0),
                120, null, null);
        assertFalse(r.conflict(), "200 天后的空档不该有任何冲突：" + r.describe());
    }

    @Test
    void excludesItselfWhenEditing() {
        ExamSchedule seeded = examService.listByCourse(courseId("CS101")).get(0);

        // 不排除自己 → 和自己撞
        assertTrue(examService.checkConflict(seeded.getCourseId(), seeded.getExamTime(),
                120, seeded.getRoomId(), null).conflict());

        // 排除自己 → 干净
        assertFalse(examService.checkConflict(seeded.getCourseId(), seeded.getExamTime(),
                120, seeded.getRoomId(), seeded.getId()).conflict());
    }

    // ==================== 校验 ====================

    @Test
    void rejectsInvalidInput() {
        ExamSchedule base = sampleExam(courseId("CS105"));

        ExamSchedule noCourse = sampleExam(null);
        assertThrows(BusinessException.class, () -> examService.create(noCourse));

        ExamSchedule unknownCourse = sampleExam(999999);
        assertThrows(BusinessException.class, () -> examService.create(unknownCourse));

        ExamSchedule badType = sampleExam(courseId("CS105"));
        badType.setExamType("BOGUS");
        assertThrows(BusinessException.class, () -> examService.create(badType));

        ExamSchedule noTime = sampleExam(courseId("CS105"));
        noTime.setExamTime(null);
        assertThrows(BusinessException.class, () -> examService.create(noTime));

        ExamSchedule zeroDuration = sampleExam(courseId("CS105"));
        zeroDuration.setDurationMinutes(0);
        assertThrows(BusinessException.class, () -> examService.create(zeroDuration));

        ExamSchedule hugeDuration = sampleExam(courseId("CS105"));
        hugeDuration.setDurationMinutes(9 * 60);
        assertThrows(BusinessException.class, () -> examService.create(hugeDuration));

        ExamSchedule badRoom = sampleExam(courseId("CS105"));
        badRoom.setRoomId(999999);
        assertThrows(BusinessException.class, () -> examService.create(badRoom));

        assertTrue(base.getExamTime() != null);
    }

    /** 同一门课同一类型只应有一场：重复排考被拒 */
    @Test
    void duplicateTypeForTheSameCourseIsRejected() {
        ExamSchedule again = sampleExam(courseId("CS101"));
        again.setExamType(ExamSchedule.TYPE_FINAL);

        BusinessException e = assertThrows(BusinessException.class, () -> examService.create(again));
        assertTrue(e.getMessage().contains("已有生效中"), e.getMessage());
    }

    /** 排到别人占用的考场会被拒（冲突即拒绝，与 P2 审批同一原则） */
    @Test
    void creatingAConflictingExamIsRejected() {
        ExamSchedule seeded = examService.listByCourse(courseId("CS101")).get(0);

        ExamSchedule clash = sampleExam(courseId("CS105"));
        clash.setExamTime(seeded.getExamTime());
        clash.setDurationMinutes(120);
        clash.setRoomId(seeded.getRoomId());
        clash.setRemark("VERIFY-P4");

        BusinessException e = assertThrows(BusinessException.class, () -> examService.create(clash));
        assertTrue(e.getMessage().contains("冲突"), e.getMessage());
        assertTrue(examService.listByCourse(courseId("CS105")).isEmpty(), "被拒后不该留下记录");
    }

    // ==================== 唯一写库的用例（自清理） ====================

    @Test
    void createUpdateAndRemoveRoundTrip() {
        ExamSchedule exam = sampleExam(courseId("CS105"));
        exam.setRemark("VERIFY-P4");

        ExamSchedule created = examService.create(exam);
        assertNotNull(created.getId());
        assertEquals(ExamSchedule.STATUS_ACTIVE, created.getStatus(), "默认有效");
        assertEquals("期末", created.getTypeLabel());
        assertEquals(1, examService.listByCourse(courseId("CS105")).size());

        created.setSeatRange("C区01-40");
        created.setInvigilator("赵六");
        examService.update(created);
        ExamSchedule reloaded = examService.get(created.getId());
        assertEquals("C区01-40", reloaded.getSeatRange());
        assertEquals("赵六", reloaded.getInvigilator());

        examService.remove(created.getId());
        assertNull(examService.get(created.getId()));
        assertTrue(examService.listByCourse(courseId("CS105")).isEmpty());
    }

    /**
     * 更新语义：必填字段不传则继承库里的值；可选字段（考场等）是**整替换**，传 null 即清空。
     *
     * <p>这条语义是刻意的：只改座位号时不必重发时间；而"考场待定"要靠传 null 表达。
     * 断言把两边都钉住，免得以后有人"顺手"改成全部判空，把"清空考场"这个能力弄没。
     */
    @Test
    void partialUpdateInheritsRequiredFieldsAndReplacesOptionalOnes() {
        ExamSchedule exam = sampleExam(courseId("CS105"));
        exam.setRemark("VERIFY-P4");
        exam.setSeatRange("C区01-40");
        exam.setInvigilator("赵六");
        ExamSchedule created = examService.create(exam);
        assertNotNull(created.getRoomId(), "样例带了考场");

        // 只给 id 与座位号：时间/时长/课程应继承，考场等可选字段应被清空
        ExamSchedule patch = new ExamSchedule();
        patch.setId(created.getId());
        patch.setSeatRange("D区01-20");
        examService.update(patch);

        ExamSchedule reloaded = examService.get(created.getId());
        assertEquals("D区01-20", reloaded.getSeatRange());
        assertEquals(created.getExamTime(), reloaded.getExamTime(), "时间不该被清空（继承库里值）");
        assertEquals(created.getDurationMinutes(), reloaded.getDurationMinutes());
        assertEquals(created.getCourseId(), reloaded.getCourseId());
        assertNull(reloaded.getRoomId(), "可选字段整替换：没传就是清空（考场待定）");
        assertNull(reloaded.getInvigilator());

        examService.remove(created.getId());
    }

    // ==================== 辅助 ====================

    /** 排在一个空档里的合法考试（不与种子里任何一场冲突）：120 天后、教1-103 */
    private ExamSchedule sampleExam(Integer courseId) {
        ExamSchedule e = new ExamSchedule();
        e.setCourseId(courseId);
        e.setExamType(ExamSchedule.TYPE_FINAL);
        e.setExamTime(LocalDateTime.now().plusDays(120).withHour(9).withMinute(0).withSecond(0).withNano(0));
        e.setDurationMinutes(120);
        e.setRoomId(roomIdByName("教1-103"));
        e.setStatus(ExamSchedule.STATUS_ACTIVE);
        return e;
    }

    private int courseId(String code) {
        Integer id = courseMapper.selectIdByCode(code);
        assertNotNull(id, "找不到课程代码 " + code + "，请先执行迁移脚本");
        return id;
    }

    private Integer roomIdByName(String roomName) {
        com.duyell.Room room = roomMapper.selectByName(roomName);
        assertNotNull(room, "找不到教室 " + roomName + "，请先执行 P2 迁移脚本");
        return room.getId();
    }
}
