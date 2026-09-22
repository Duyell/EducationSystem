package duyell.service;

import com.duyell.Score;
import duyell.mapper.CourseMapper;
import duyell.mapper.ScoreMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成绩增删的回归测试。
 *
 * <p><b>为什么单独测"删除"</b>：浏览器层验证学业预警时，脚本删不掉自己造的夹具成绩，
 * 排查发现 {@code ScoreMapper.deleteByIds} 的参数名是 {@code courseIds}，而 XML 里写的是
 * {@code <foreach collection="ids">}——MyBatis 按参数名匹配集合，于是**成绩删除一直是 500**
 * （接口回 {@code code:500 系统繁忙}）。这是本项目"规则/映射只有一处实现"之外的另一类问题：
 * **接口签名与 XML 的隐式契约没有测试盯着**。
 *
 * <p>这类缺陷编译期与单元测试都发现不了（本项目此前没有任何用例调用过删除），
 * 所以这里把它钉住：删除后再查必须为空。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ScoreServiceCrudTest {

    @Autowired
    private ScoreService scoreService;

    @Autowired
    private ScoreMapper scoreMapper;

    @Autowired
    private CourseMapper courseMapper;

    private static final String STUDENT = "2024003";

    /** 造一条成绩（总分由服务端计算，passed 派生字段也由服务端维护） */
    private Score seed(int courseId) {
        Score s = new Score();
        s.setCourseId(courseId);
        s.setStudentId(STUDENT);
        s.setUsualScore(new BigDecimal("80"));
        s.setExamScore(new BigDecimal("90"));
        scoreService.add(s);
        return scoreMapper.select(courseId, Integer.valueOf(STUDENT));
    }

    private int anyCourseId() {
        List<com.duyell.Course> courses = courseMapper.list(null, null, null, null, null, null, null);
        assertNotNull(courses);
        assertTrue(courses.size() > 0, "课程表为空，测试无法进行");
        return courses.get(0).getId();
    }

    @Test
    void addThenDeleteRemovesTheRow() {
        int courseId = anyCourseId();
        Score saved = seed(courseId);
        assertNotNull(saved, "成绩应已落库");
        assertNotNull(saved.getId(), "应回填主键");
        assertEquals(0, saved.getTotalScore().compareTo(new BigDecimal("86.000")),
                "总分 = 80×0.4 + 90×0.6 = 86.000，实际 " + saved.getTotalScore());

        // 关键断言：按 id 删除必须真的删掉（曾经因 XML 与参数名不匹配而 500）
        scoreService.delete(saved.getId());

        assertNull(scoreMapper.select(courseId, Integer.valueOf(STUDENT)),
                "删除后不应再查到该成绩——若失败，多半是 ScoreMapper.deleteByIds 的参数名与 XML 的 collection 不匹配");
    }

    /** XML 用的是 foreach 批量删除，多 id 路径同样要能跑通 */
    @Test
    void deleteAcceptsSeveralIds() {
        List<com.duyell.Course> courses = courseMapper.list(null, null, null, null, null, null, null);
        int first = courses.get(0).getId();
        int second = courses.size() > 1 ? courses.get(1).getId() : first;
        Score a = seed(first);
        Score b = seed(second);
        assertNotNull(a.getId());

        // 逐个删（服务层入口只暴露单个 id，这里顺带回归"同一条 SQL 被调用多次"的情形）
        scoreService.delete(a.getId());
        if (second != first) {
            scoreService.delete(b.getId());
            assertNull(scoreMapper.select(second, Integer.valueOf(STUDENT)));
        }
        assertNull(scoreMapper.select(first, Integer.valueOf(STUDENT)));
    }
}
