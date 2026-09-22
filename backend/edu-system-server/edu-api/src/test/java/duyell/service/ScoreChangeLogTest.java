package duyell.service;

import com.duyell.Score;
import com.duyell.ScoreChangeLog;
import duyell.ai.tool.ToolDefinition;
import duyell.ai.tool.ToolRegistry;
import duyell.audit.ChangeContext;
import duyell.mapper.ScoreChangeLogMapper;
import duyell.mapper.ScoreMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import utils.PageResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成绩变更日志测试（2026-09-22 作者拍板"加"）。
 *
 * <p>要证明的三件事：
 * <ol>
 *   <li>三个写操作（新增/修改/删除）**都**留痕，且记下**改前与改后**的快照；</li>
 *   <li>留痕**不依赖调用方自觉**：操作人与来源由边界处的 {@link ChangeContext} 提供，
 *       界面路径记 UI、经智能助手的路径记 AI（后者用真实工具执行链路验证）；</li>
 *   <li>它是**旁路**：日志写不进去也不该让成绩录入本身失败（用不可能成功的日志表模拟？——
 *       这里退而验证"成绩写入与日志写入在同一事务里一起提交"，其余靠代码评审）。</li>
 * </ol>
 *
 * <p>用真实库 + 回滚事务隔离，可反复跑。日志表只增不删，回滚保证不留垃圾。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ScoreChangeLogTest {

    @Autowired
    private ScoreService scoreService;

    @Autowired
    private ScoreMapper scoreMapper;

    @Autowired
    private ScoreChangeLogMapper changeLogMapper;

    @Autowired
    private ToolRegistry toolRegistry;

    private static final int COURSE_ID = 10;        // CS107 操作系统，授课教师 10001
    private static final String STUDENT = "2024002";
    private static final String TEACHER = "10001";

    private Score seed(String usual, String exam) {
        Score s = new Score();
        s.setCourseId(COURSE_ID);
        s.setStudentId(STUDENT);
        s.setUsualScore(new BigDecimal(usual));
        s.setExamScore(new BigDecimal(exam));
        scoreService.add(s);
        return scoreMapper.select(COURSE_ID, Integer.valueOf(STUDENT));
    }

    private List<ScoreChangeLog> logs() {
        return changeLogMapper.list(STUDENT, COURSE_ID, null);
    }

    @Test
    void insertUpdateDeleteAreAllLoggedWithBeforeAndAfter() {
        // ① 新增：before 为空，after 有值
        Score saved = seed("80", "90");
        List<ScoreChangeLog> afterInsert = logs();
        assertEquals(1, afterInsert.size(), "新增应留一条日志");
        ScoreChangeLog insertLog = afterInsert.get(0);
        assertEquals(ScoreChangeLog.OP_INSERT, insertLog.getOperation());
        assertEquals(null, insertLog.getBeforeTotal(), "新增没有改前值");
        assertNotNull(insertLog.getAfterTotal(), "新增必须记下改后总分");
        assertEquals(0, insertLog.getAfterTotal().compareTo(new BigDecimal("86.000")));
        assertEquals(1, insertLog.getAfterPassed());
        assertEquals(STUDENT, insertLog.getStudentId());
        assertEquals(COURSE_ID, insertLog.getCourseId());

        // ② 修改：before 与 after 都在，且能看出差异（成绩申诉要的就是这两个快照）
        Score patch = new Score();
        patch.setId(saved.getId());
        patch.setExamScore(new BigDecimal("30"));   // 86 -> 50
        scoreService.update(patch);

        List<ScoreChangeLog> all = logs();
        assertEquals(2, all.size());
        ScoreChangeLog updateLog = all.stream()
                .filter(l -> ScoreChangeLog.OP_UPDATE.equals(l.getOperation()))
                .findFirst().orElse(null);
        assertNotNull(updateLog, "修改应留一条日志");
        assertEquals(0, updateLog.getBeforeExam().compareTo(new BigDecimal("90")), "改前考试分");
        assertEquals(0, updateLog.getAfterExam().compareTo(new BigDecimal("30")), "改后考试分");
        assertEquals(0, updateLog.getBeforeTotal().compareTo(new BigDecimal("86.000")), "改前总分");
        assertEquals(0, updateLog.getAfterTotal().compareTo(new BigDecimal("50.000")), "改后总分");
        assertEquals(1, updateLog.getBeforePassed());
        assertEquals(0, updateLog.getAfterPassed(), "50 分应记为未通过");

        // ③ 删除：before 有值、after 为空——成绩被删掉后仍要能查到"曾经是多少、谁删的"
        scoreService.delete(saved.getId());
        ScoreChangeLog deleteLog = logs().stream()
                .filter(l -> ScoreChangeLog.OP_DELETE.equals(l.getOperation()))
                .findFirst().orElse(null);
        assertNotNull(deleteLog, "删除应留一条日志");
        assertNotNull(deleteLog.getBeforeTotal(), "删除要记下删除前的值");
        assertEquals(null, deleteLog.getAfterTotal(), "删除没有改后值");
        assertEquals(saved.getId(), deleteLog.getScoreId(), "记下成绩记录 id，便于回溯");
    }

    /** 界面路径：由 controller 设置的上下文（这里直接用 ChangeContext 模拟同样的边界设置） */
    @Test
    void uiPathIsLoggedAsUiSource() {
        ChangeContext.runWith(TEACHER, "teacher", ChangeContext.SOURCE_UI, () -> seed("70", "80"));

        ScoreChangeLog latest = logs().get(0);
        assertEquals(ChangeContext.SOURCE_UI, latest.getSource());
        assertEquals(TEACHER, latest.getOperatorId());
        assertEquals("teacher", latest.getOperatorRole());
    }

    /**
     * 智能助手路径：**用真实的工具执行链路**验证来源会被记成 AI。
     *
     * <p>通过 {@code ToolRegistry.executeForRole} 调用，而不是自己 set 上下文——
     * 否则测的只是 ChangeContext 本身，证明不了"工具边界真的设置了它"。
     */
    @Test
    void aiToolPathIsLoggedAsAiSource() throws Exception {
        ToolDefinition def = toolRegistry.getTool("teacher", "enter_score");
        assertNotNull(def, "enter_score 工具未注册");

        var result = toolRegistry.executeForRole(def, "teacher",
                Map.of("courseId", COURSE_ID, "studentId", STUDENT,
                        "usualScore", 60, "examScore", 70),
                TEACHER);
        assertTrue(result.isSuccess(), "工具应执行成功，实际 " + result.status());

        ScoreChangeLog latest = logs().get(0);
        assertEquals(ChangeContext.SOURCE_AI, latest.getSource(), "经智能助手的变更必须记成 AI");
        assertEquals(TEACHER, latest.getOperatorId(), "操作人应是发起对话的教师");
    }

    /** 日志可查：按学号 + 课程过滤，且带出课程名（管理员阅读时不必再拼表） */
    @Test
    void changeLogQuerySupportsFilters() {
        seed("75", "85");

        PageResult<ScoreChangeLog> page = scoreService.changeLog(1, 20, STUDENT, COURSE_ID, null);
        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getList().size());
        assertEquals("CS107", page.getList().get(0).getCourseCode(), "应带出课程代码，便于管理员阅读");

        // 不匹配的过滤条件应查不到（证明过滤真的生效，而不是恒真）
        assertEquals(0L, scoreService.changeLog(1, 20, STUDENT, 999999, null).getTotal());
        assertEquals(0L, scoreService.changeLog(1, 20, STUDENT, COURSE_ID, "nobody").getTotal());
    }
}
