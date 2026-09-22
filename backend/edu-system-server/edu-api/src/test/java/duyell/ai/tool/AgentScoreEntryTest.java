package duyell.ai.tool;

import com.duyell.Course;
import com.duyell.Score;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.ScoreMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 经 **AI 助手**录入/修改成绩的回归测试。
 *
 * <p><b>为什么要单独测</b>：写政策文档（`docs/policies/09-教师成绩录入与修改规范.md`）时逐条核对系统行为，
 * 发现助手侧的成绩工具**绕过**了 {@code ScoreService} 自己算总分，于是有两个真实缺陷：
 * <ol>
 *   <li>{@code enter_score} 不写 {@code passed} → 落库为 NULL，而"是否已修过"的选课校验
 *       用的是 {@code passed = 1}（{@code ScoreMapper.countPassedByCourseCode}），
 *       于是**学生被录完成绩后还能重复选同一门课**；</li>
 *   <li>总分精度不一致：助手路径 {@code setScale(1)}，而 {@code ScoreServiceImpl} 是 3 位小数
 *       （与 {@code score.total_score decimal(6,3)} 对齐）——**同一份成绩走两条路径得到不同总分**。</li>
 * </ol>
 * 两处都是"规则被实现了两遍"，修法是让工具走 {@code ScoreService}（规则只留一处）。
 *
 * <p>本测试会**写真实成绩表**，因此用默认回滚事务隔离（与审计测试刻意相反：那个必须真正提交才能验证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AgentScoreEntryTest {

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private ScoreMapper scoreMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TEACHER = "10001";
    private static final String STUDENT = "2024002";

    /** 取一门该教师名下、且这个学生**尚无成绩**的课程（唯一约束是 course_id + student_id） */
    private Course fixtureCourse() {
        for (Course c : courseMapper.selectByTeacherId(TEACHER)) {
            if (scoreMapper.select(c.getId(), Integer.valueOf(STUDENT)) == null) {
                return c;
            }
        }
        fail("找不到可用于测试的课程：教师 " + TEACHER + " 的所有课程上学生 " + STUDENT + " 都已有成绩");
        return null;
    }

    private Map<String, Object> run(String tool, Map<String, Object> args) throws Exception {
        ToolDefinition def = registry.getTool("teacher", tool);
        assertNotNull(def, tool + " 未注册");
        ToolExecutionResult result = registry.executeForRole(def, "teacher", args, TEACHER);
        assertTrue(result.isSuccess(), "工具应以业务载荷返回，实际 " + result.status());
        return objectMapper.readValue(result.payload(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /**
     * 核心回归：经助手录入的成绩必须带 {@code passed = 1}，否则"已修过"校验会漏判。
     */
    @Test
    void scoreEnteredViaAgentIsMarkedPassed() throws Exception {
        Course course = fixtureCourse();

        Map<String, Object> out = run("enter_score", args(
                "courseId", course.getId(), "studentId", STUDENT,
                "usualScore", 87.65, "examScore", 91.35));
        assertFalse(out.containsKey("error"), String.valueOf(out));

        Score saved = scoreMapper.select(course.getId(), Integer.valueOf(STUDENT));
        assertNotNull(saved, "成绩应已落库");
        assertEquals(1, saved.getPassed(), "及格成绩必须写 passed=1（否则选课『已修过』校验漏判）");
        assertTrue(saved.getPassed() == 1 && saved.getTotalScore().signum() > 0, "passed 与总分都必须写入");
    }

    /**
     * 精度回归：与 {@code ScoreServiceImpl} 一致（3 位小数），而不是助手自己算的 1 位小数。
     * 87.65×0.4 + 91.35×0.6 = 89.87 → 存 89.870（曾经存 89.9）。
     */
    @Test
    void totalScoreUsesTheSamePrecisionAsTheService() throws Exception {
        Course course = fixtureCourse();

        Map<String, Object> out = run("enter_score", args(
                "courseId", course.getId(), "studentId", STUDENT,
                "usualScore", 87.65, "examScore", 91.35));
        assertFalse(out.containsKey("error"), String.valueOf(out));

        Score saved = scoreMapper.select(course.getId(), Integer.valueOf(STUDENT));
        assertNotNull(saved);
        assertEquals(0, saved.getTotalScore().compareTo(new java.math.BigDecimal("89.870")),
                "总分应为 89.870（3 位小数），实际 " + saved.getTotalScore());
        assertEquals(3, saved.getTotalScore().scale(), "精度必须与 score.total_score decimal(6,3) 对齐");
    }

    /**
     * 只改一项时：另一项保留、总分重算、{@code passed} 不被清空。
     * （{@code ScoreMapper.update} 用的是动态列，本身不会把 passed 写成 NULL，
     * 但助手路径曾经连 passed 都不传，这条断言把它钉住。）
     */
    @Test
    void updatingOneItemKeepsTheOtherAndRepasses() throws Exception {
        Course course = fixtureCourse();
        run("enter_score", args("courseId", course.getId(), "studentId", STUDENT,
                "usualScore", 87.65, "examScore", 91.35));
        Score before = scoreMapper.select(course.getId(), Integer.valueOf(STUDENT));
        assertNotNull(before);

        // 只改考试成绩：87.65×0.4 + 95×0.6 = 92.06 → 92.060
        Map<String, Object> out = run("update_score", args("id", before.getId(), "examScore", 95.0));
        assertFalse(out.containsKey("error"), String.valueOf(out));

        Score after = scoreMapper.select(course.getId(), Integer.valueOf(STUDENT));
        assertNotNull(after);
        assertEquals(0, after.getUsualScore().compareTo(new java.math.BigDecimal("87.65")),
                "未传的平时成绩不能被清零");
        assertEquals(0, after.getTotalScore().compareTo(new java.math.BigDecimal("92.060")),
                "总分应重算为 92.060，实际 " + after.getTotalScore());
        assertEquals(1, after.getPassed(), "修改后 passed 仍应为 1");
    }

    /** 不及格成绩同样要写 passed=0（不能是 NULL），否则"未通过"与"未录成绩"无法区分 */
    @Test
    void failingScoreIsMarkedNotPassed() throws Exception {
        Course course = fixtureCourse();
        run("enter_score", args("courseId", course.getId(), "studentId", STUDENT,
                "usualScore", 50.0, "examScore", 55.0));

        Score saved = scoreMapper.select(course.getId(), Integer.valueOf(STUDENT));
        assertNotNull(saved);
        assertEquals(0, saved.getPassed(), "不及格必须写 passed=0，不能留 NULL");
    }
}
