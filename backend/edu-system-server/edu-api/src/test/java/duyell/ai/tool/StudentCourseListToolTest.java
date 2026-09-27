package duyell.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学生「我的已选课程」工具的**数据正确性**回归测试。
 *
 * <p>为什么单独为它写一条：工具面（{@code AgentToolSurfaceTest}）只验证"工具在不在、角色对不对"，
 * 评测脚本断言的是"模型有没有选对工具"——**没有一处**验证"选中的工具真的返回了数据"。
 * 于是当它在真机上返回 {@code []} 时（2026-09-22 M2 端到端验证中 model 调了它、审计里
 * {@code result_json=[]}，而该生实际已选 6 门课），整套测试仍是绿的。
 *
 * <p>断言落在**业务数据**上（课程代码），而不是"非空"或 HTTP 状态：
 * 空列表恰好就是这个 bug 的表现形态，断言"非空"虽然也能抓到，但说不清错在哪。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class StudentCourseListToolTest {

    private static final String STUDENT = "2023001";

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private duyell.mapper.CourseSelectionMapper courseSelectionMapper;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void selectedCoursesToolReturnsTheStudentsRealSelections() throws Exception {
        ToolDefinition def = toolRegistry.getTool("student", "get_my_courses");
        assertNotNull(def, "get_my_courses 工具未注册");

        ToolExecutionResult result = toolRegistry.executeForRole(def, "student", Map.of(), STUDENT);
        assertTrue(result.isSuccess(), "工具应执行成功，实际 " + result.status() + " / " + result.errorDetail());

        List<Map<String, Object>> rows = objectMapper.readValue(result.payload(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });

        // 期望值**从库里现算**，不再硬编码课程集合。
        //
        // 这里踩过一个坑（CI 一直红、本机一直绿）：原断言写的是"应包含 1~6"，
        // 而种子数据其实只给这个学生选了 1~4——5、6 是**本机跑演示脚本时选上的**。
        // 也就是说那条测试断言的是"我这台机器的数据库长什么样"，在干净库上必然失败。
        // 改成"与这个学生真实的选课集合逐项相等"之后：既不再依赖环境，
        // 也比原来的 containsAll 更强（多返回了别人的课同样会被抓住，
        // 而这正是 2026-09-22 那个"问选了什么课、答没选任何课"bug 的家族）。
        List<Integer> expected = courseSelectionMapper.selectByStudentId(STUDENT).stream()
                .map(com.duyell.CourseSelection::getCourseId)
                .sorted()
                .toList();
        assertFalse(expected.isEmpty(), "种子数据里该生应有选课记录，否则这条测试失去意义");

        List<Integer> courseIds = rows.stream()
                .map(row -> ((Number) row.get("courseId")).intValue())
                .sorted()
                .toList();
        assertEquals(expected, courseIds, "工具应返回该生真实的选课集合（查错人/查空表都会在这里现形）");
        // 再钉一个种子数据里明确存在的锚点：Java 程序设计（课程 1）
        assertTrue(courseIds.contains(1), "至少应包含种子数据里明确给该生的课程 1，实际=" + courseIds);

        // 带上课程名（而不是只有 id）：模型要靠它组织回答，只有 id 等于把"翻译"工作丢回给模型
        assertTrue(rows.stream().anyMatch(row -> "Java程序设计".equals(row.get("courseName"))),
                "应带出课程名，实际=" + rows);
        assertTrue(rows.stream().anyMatch(row -> row.get("teacherName") != null && row.get("term") != null),
                "应带出教师与学期，实际=" + rows);
    }

    /** 反向：一个没有任何选课记录的学生，必须返回空数组而不是别人的数据 */
    @Test
    void studentWithoutSelectionsGetsEmptyList() throws Exception {
        ToolDefinition def = toolRegistry.getTool("student", "get_my_courses");
        ToolExecutionResult result = toolRegistry.executeForRole(def, "student", Map.of(), "2099001");
        assertTrue(result.isSuccess(), "工具应执行成功");
        assertTrue(result.payload().trim().equals("[]"),
                "没有选课记录的学生应得到空数组，实际=" + result.payload());
    }
}
