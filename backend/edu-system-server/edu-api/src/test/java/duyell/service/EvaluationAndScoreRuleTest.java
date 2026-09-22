package duyell.service;

import com.duyell.Course;
import com.duyell.Score;
import com.duyell.TeacherEvaluation;
import duyell.mapper.CourseMapper;
import duyell.mapper.CourseSelectionMapper;
import duyell.mapper.EvaluationMapper;
import duyell.mapper.ScoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;
import utils.PageResult;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-09-22 作者确认的两条规则 + 成绩范围校验的回归测试。
 *
 * <p>三件事此前**都只有界面约束、服务端不拦**（"看起来有规矩、实际能绕过"）：
 * <ol>
 *   <li>评教**匿名**：教师看得到内容，看不到提交人；</li>
 *   <li>评教**归属**：只能评价本人已选课程的授课教师，且评价对象由课程决定；</li>
 *   <li>成绩**范围 0~100**。</li>
 * </ol>
 *
 * <p><b>夹具选择很关键</b>：种子数据里 2023001 已选课程 1~6，但只评价过课程 1 与 4；
 * 因此本测试用**课程 2（SpringBoot开发，授课教师 10002）**——"已选但未评价"，
 * 才能验证"能评价"与"不能重复评价"。这条前置条件由
 * {@link #fixturePreconditions()} 显式断言，避免种子一变就"因为别的原因"通过/失败。
 *
 * <p>本测试写真实库，用回滚事务隔离（结束后原样恢复；因此不需要清理代码）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class EvaluationAndScoreRuleTest {

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private ScoreService scoreService;

    @Autowired
    private EvaluationMapper evaluationMapper;

    @Autowired
    private ScoreMapper scoreMapper;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private CourseSelectionMapper courseSelectionMapper;

    /** 学生 2023001 已选、且**尚未评价**的课程 */
    private static final int COURSE_ID = 2;
    private static final String STUDENT = "2023001";
    /** 另一名教师（用于验证"评价对象由课程决定"） */
    private static final String OTHER_TEACHER = "10005";
    /** 2023001 **没有**选的课程（用于验证归属校验） */
    private static final int NOT_SELECTED_COURSE = 10;

    @BeforeEach
    void clearScoreFixture() {
        // 课程 2 上 2023001 在种子里已有一条成绩，先删掉，避免插入撞唯一键；
        // 事务结束回滚 → 真实数据不受影响。
        scoreMapper.delete(COURSE_ID, Integer.valueOf(STUDENT));
    }

    /** 夹具前置条件：本类的结论都建立在它之上，断言出来免得"因为别的原因"通过 */
    @Test
    void fixturePreconditions() {
        assertNotNull(courseSelectionMapper.select(COURSE_ID, STUDENT),
                "前置条件：2023001 应已选课程 " + COURSE_ID);
        assertNull(evaluationMapper.selectByCourseAndStudent(COURSE_ID, STUDENT),
                "前置条件：2023001 对课程 " + COURSE_ID + " 应尚未评价（否则'能评价'的用例会变成'重复评价'）");
        assertNull(courseSelectionMapper.select(NOT_SELECTED_COURSE, STUDENT),
                "前置条件：2023001 不应选了课程 " + NOT_SELECTED_COURSE + "（归属校验用例要用它）");
    }

    // ---------------------------------------------------------------- 评教归属

    @Test
    void cannotEvaluateACourseNotSelected() {
        TeacherEvaluation eval = new TeacherEvaluation();
        eval.setCourseId(NOT_SELECTED_COURSE);
        eval.setStudentId(STUDENT);
        eval.setScore(5);

        BusinessException ex = assertThrows(BusinessException.class, () -> evaluationService.add(eval));
        assertTrue(ex.getMessage().contains("还没有选这门课"), ex.getMessage());
    }

    @Test
    void teacherIsDecidedByTheCourseNotByTheCaller() {
        TeacherEvaluation eval = new TeacherEvaluation();
        eval.setCourseId(COURSE_ID);
        eval.setStudentId(STUDENT);
        eval.setTeacherId(OTHER_TEACHER);   // 故意传一个别的老师
        eval.setScore(5);
        eval.setContent("讲得清楚");

        evaluationService.add(eval);

        TeacherEvaluation saved = evaluationMapper.selectByCourseAndStudent(COURSE_ID, STUDENT);
        assertNotNull(saved);
        Course course = courseMapper.selectCourseById(COURSE_ID);
        assertEquals(course.getTeacherId(), saved.getTeacherId(),
                "评价对象必须由课程决定，不能被调用方传入的 teacherId 带偏");
    }

    @Test
    void cannotEvaluateTheSameCourseTwice() {
        TeacherEvaluation first = new TeacherEvaluation();
        first.setCourseId(COURSE_ID);
        first.setStudentId(STUDENT);
        first.setScore(4);
        evaluationService.add(first);

        TeacherEvaluation again = new TeacherEvaluation();
        again.setCourseId(COURSE_ID);
        again.setStudentId(STUDENT);
        again.setScore(5);

        BusinessException ex = assertThrows(BusinessException.class, () -> evaluationService.add(again));
        assertTrue(ex.getMessage().contains("已经评价过"), ex.getMessage());
    }

    // ---------------------------------------------------------------- 评教匿名

    @Test
    void teacherViewHidesWhoSubmitted() {
        TeacherEvaluation eval = new TeacherEvaluation();
        eval.setCourseId(COURSE_ID);
        eval.setStudentId(STUDENT);
        eval.setScore(5);
        eval.setContent("匿名测试");
        evaluationService.add(eval);

        String teacherId = courseMapper.selectCourseById(COURSE_ID).getTeacherId();

        // 教师视角（分页）
        PageResult<TeacherEvaluation> page = evaluationService.pageForTeacher(1, 50, teacherId);
        List<TeacherEvaluation> rows = page.getList();
        assertTrue(rows.stream().anyMatch(r -> "匿名测试".equals(r.getContent())), "教师应能看到内容");
        for (TeacherEvaluation row : rows) {
            assertNull(row.getStudentId(), "教师视角不得返回提交人学号");
            assertNull(row.getStudentName(), "教师视角不得返回提交人姓名");
        }

        // 教师视角（列表：AI 助手工具走这条）
        for (TeacherEvaluation row : evaluationService.listForTeacher(teacherId)) {
            assertNull(row.getStudentId(), "教师视角（列表）不得返回提交人学号");
            assertNull(row.getStudentName(), "教师视角（列表）不得返回提交人姓名");
        }

        // 学生查自己的评价时仍然看得到（不必对自己匿名）
        List<TeacherEvaluation> mine = evaluationService.list(null, STUDENT, null);
        assertTrue(mine.stream().anyMatch(r -> STUDENT.equals(r.getStudentId())), "学生应能看到自己的评价记录");
    }

    // ---------------------------------------------------------------- 成绩范围

    @Test
    void scoreOutOfRangeIsRejected() {
        Score tooHigh = new Score();
        tooHigh.setCourseId(COURSE_ID);
        tooHigh.setStudentId(STUDENT);
        tooHigh.setUsualScore(new BigDecimal("101"));
        tooHigh.setExamScore(new BigDecimal("80"));
        BusinessException ex1 = assertThrows(BusinessException.class, () -> scoreService.add(tooHigh));
        assertTrue(ex1.getMessage().contains("0~100"), ex1.getMessage());

        Score tooLow = new Score();
        tooLow.setCourseId(COURSE_ID);
        tooLow.setStudentId(STUDENT);
        tooLow.setUsualScore(new BigDecimal("-1"));
        BusinessException ex2 = assertThrows(BusinessException.class, () -> scoreService.add(tooLow));
        assertTrue(ex2.getMessage().contains("0~100"), ex2.getMessage());
    }

    @Test
    void boundaryValuesAreAccepted() {
        Score zero = new Score();
        zero.setCourseId(COURSE_ID);
        zero.setStudentId(STUDENT);
        zero.setUsualScore(BigDecimal.ZERO);
        zero.setExamScore(new BigDecimal("100"));
        scoreService.add(zero);

        Score saved = scoreMapper.select(COURSE_ID, Integer.valueOf(STUDENT));
        assertNotNull(saved);
        assertEquals(0, saved.getTotalScore().compareTo(new BigDecimal("60.000")),
                "0×0.4 + 100×0.6 = 60.000，边界值应被接受");
    }

    /** 修改成绩同样受范围约束（update 路径不能绕过校验） */
    @Test
    void updateOutOfRangeIsRejected() {
        Score s = new Score();
        s.setCourseId(COURSE_ID);
        s.setStudentId(STUDENT);
        s.setUsualScore(new BigDecimal("70"));
        s.setExamScore(new BigDecimal("80"));
        scoreService.add(s);
        Score saved = scoreMapper.select(COURSE_ID, Integer.valueOf(STUDENT));

        Score patch = new Score();
        patch.setId(saved.getId());
        patch.setExamScore(new BigDecimal("150"));

        BusinessException ex = assertThrows(BusinessException.class, () -> scoreService.update(patch));
        assertTrue(ex.getMessage().contains("0~100"), ex.getMessage());

        // 失败后库里仍是原值（校验发生在写库之前）
        Score after = scoreMapper.select(COURSE_ID, Integer.valueOf(STUDENT));
        assertEquals(0, after.getExamScore().compareTo(new BigDecimal("80")), "校验失败不应改动原值");
    }
}
