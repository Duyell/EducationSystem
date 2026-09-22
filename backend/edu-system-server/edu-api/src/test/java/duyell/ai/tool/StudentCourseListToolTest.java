package duyell.ai.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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

        // 该生（种子数据）已选课程 1~6。断言"包含"而不是"恰好等于"：
        // 真机跑智能助手选课时会给这个学生再加课，钉死数量会让测试变成脆弱测试。
        List<Integer> courseIds = rows.stream()
                .map(row -> ((Number) row.get("courseId")).intValue()).toList();
        assertTrue(courseIds.containsAll(List.of(1, 2, 3, 4, 5, 6)),
                "应返回该生已选的课程 1~6，实际=" + courseIds);
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
