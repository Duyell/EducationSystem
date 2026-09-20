package duyell.ai.tool;

import com.duyell.Course;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.mapper.CourseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code check_time_conflict} 的**参数解析**测试。
 *
 * <p><b>为什么单独测这个</b>：真实 LLM 评测（`.dsh/eval-p5-tools.cjs`）抓到一次"选对了工具、
 * 却给了编造的参数"——学生问"数据结构与算法这门课和我课表冲突吗"，7B 模型没有先查课程列表，
 * 而是直接编了一个 {@code courseId=101}（真实 id 是 3），工具返回"课程不存在"，
 * 用户什么也没得到。根因是工具**只收 courseId**，而用户只会说课程名。
 *
 * <p>现在三种标识任选其一。这里把三条解析路径与三类错误都钉住：
 * 编造 ID / 没给标识 / 查不到的课名，都必须返回**可读且可自救**的错误，而不是静默算错或抛异常。
 * 特别地，"多个学期命中"会列出候选——安静地挑了另一个学期是最难发现的错。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckTimeConflictResolutionTest {

    @Autowired
    private ToolRegistry registry;

    @Autowired
    private CourseMapper courseMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String STUDENT = "2023001";

    private Map<String, Object> payload(Map<String, Object> args) throws Exception {
        ToolDefinition def = registry.getTool("check_time_conflict");
        assertNotNull(def, "check_time_conflict 未注册");
        ToolExecutionResult result = registry.executeForRole(def, "student", args, STUDENT);
        assertTrue(result.isSuccess(), "工具应以业务载荷返回（错误也走载荷），实际: " + result.status());
        assertNotNull(result.payload());
        return objectMapper.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
    }

    /** 取一门真实存在的课（用库里的数据，避免把测试钉死在某个 id 上） */
    private Course anyCourse() {
        List<Course> courses = courseMapper.list(null, null, null, null, null, null, null);
        assertFalse(courses.isEmpty(), "课程表为空，测试无法进行（需要 seed_data.sql）");
        return courses.get(0);
    }

    @Test
    void resolvesByCourseCode() throws Exception {
        Course course = anyCourse();
        Map<String, Object> out = payload(Map.of("courseCode", course.getCourseCode()));

        assertFalse(out.containsKey("error"), "按课程代码应能解析: " + out);
        assertEquals(course.getCourseCode(), out.get("courseCode"));
        assertEquals(String.valueOf(course.getId()), String.valueOf(out.get("courseId")));
    }

    @Test
    void resolvesByCourseName() throws Exception {
        Course course = anyCourse();
        Map<String, Object> out = payload(Map.of("courseName", course.getCourseName()));

        assertFalse(out.containsKey("error"), "按课程名应能解析（用户就是这么问的）: " + out);
        assertEquals(course.getCourseCode(), out.get("courseCode"));
    }

    @Test
    void resolvesByCourseIdAsBefore() throws Exception {
        Course course = anyCourse();
        Map<String, Object> out = payload(Map.of("courseId", course.getId()));

        assertFalse(out.containsKey("error"), "按 courseId 仍应可用（向后兼容）: " + out);
        assertEquals(course.getCourseCode(), out.get("courseCode"));
    }

    /** 编造的 id：必须明确说"不存在"，并告诉模型还能怎么问 */
    @Test
    void unknownCourseIdExplainsHowToRecover() throws Exception {
        Map<String, Object> out = payload(Map.of("courseId", 999999));

        assertTrue(out.containsKey("error"), "不存在的课程应返回 error: " + out);
        String error = String.valueOf(out.get("error"));
        assertTrue(error.contains("不存在"), error);
        assertTrue(error.contains("courseCode") || error.contains("courseName"),
                "错误里必须给出可自救的替代参数: " + error);
    }

    @Test
    void unknownCourseNameIsAnErrorNotAnEmptyAnswer() throws Exception {
        Map<String, Object> out = payload(Map.of("courseName", "不存在的课程名XYZ"));

        assertTrue(out.containsKey("error"), "查不到的课名应返回 error: " + out);
        assertTrue(String.valueOf(out.get("error")).contains("未找到"), String.valueOf(out.get("error")));
    }

    @Test
    void missingAllIdentifiersIsAnError() throws Exception {
        Map<String, Object> out = payload(Map.of());

        assertTrue(out.containsKey("error"), "不给任何标识应返回 error: " + out);
        assertTrue(String.valueOf(out.get("error")).contains("任选其一"), String.valueOf(out.get("error")));
    }

    /**
     * 同一课程代码命中多个学期时，必须取最近学期**并**回显候选。
     *
     * <p>这条最值得测：安静地挑了另一个学期的课表去比对，结果看起来永远"合理"，
     * 用户不可能发现。所以这里真的插一条更晚学期的同代码课程，断言三件事——
     * 候选被列出、选中的是更晚的学期、并且给了一句解释。
     */
    @Test
    void multipleTermsPreferTheLatestAndListCandidates() throws Exception {
        Course base = anyCourse();
        Course copy = new Course();
        copy.setCourseCode(base.getCourseCode());
        copy.setCourseName(base.getCourseName());
        copy.setTeacherId(base.getTeacherId());
        copy.setCollegeId(base.getCollegeId());
        copy.setCredit(base.getCredit());
        copy.setClassHour(base.getClassHour());
        copy.setMaxStudent(base.getMaxStudent());
        copy.setTerm("2099-2099-1"); // 故意比库里所有学期都晚
        courseMapper.add(copy);
        assertNotNull(copy.getId(), "插入的临时课程应回填自增 id");
        try {
            Map<String, Object> out = payload(Map.of("courseCode", base.getCourseCode()));

            assertFalse(out.containsKey("error"), String.valueOf(out));
            assertEquals("2", String.valueOf(out.get("candidateCount")), "应报告有两个学期的候选: " + out);
            assertTrue(out.get("candidates") instanceof List, "候选必须是列表: " + out);
            assertEquals(2, ((List<?>) out.get("candidates")).size(), String.valueOf(out));
            assertEquals("2099-2099-1", String.valueOf(out.get("term")),
                    "多个学期时应取最近学期，否则会安静地比对错误学期的课表: " + out);
            assertNotNull(out.get("note"), "必须解释为什么是这门课: " + out);
        } finally {
            courseMapper.delete(copy.getId());
        }
    }

    /** 触发冲突判定的完整路径：只要课程存在，就必须给出 conflict 结论而不是报错 */
    @Test
    void alwaysAnswersConflictForAnExistingCourse() throws Exception {
        Course course = anyCourse();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("courseCode", course.getCourseCode());
        Map<String, Object> out = payload(args);

        assertFalse(out.containsKey("error"), String.valueOf(out));
        assertTrue(out.containsKey("conflict"), "必须给出 conflict 字段: " + out);
        assertTrue(out.containsKey("message"), "必须给出一句可读结论: " + out);
    }
}
