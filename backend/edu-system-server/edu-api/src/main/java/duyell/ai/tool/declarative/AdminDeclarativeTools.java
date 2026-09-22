package duyell.ai.tool.declarative;

import com.duyell.Clazz;
import com.duyell.College;
import com.duyell.Course;
import com.duyell.CourseApply;
import com.duyell.Major;
import com.duyell.Student;
import com.duyell.SysUser;
import com.duyell.Teacher;
import com.fasterxml.jackson.databind.ObjectMapper;
import duyell.ai.tool.RiskLevel;
import duyell.mapper.ClazzMapper;
import duyell.mapper.CollegeMapper;
import duyell.mapper.CourseMapper;
import duyell.mapper.MajorMapper;
import duyell.mapper.StudentMapper;
import duyell.mapper.SysUserMapper;
import duyell.mapper.TeacherMapper;
import duyell.service.CourseApplyService;
import duyell.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import utils.BusinessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员工具的**声明式实现**（M2 计划 1.3 批量迁移：管理员全量）。
 *
 * <p><b>迁移范围</b>：{@code AdminToolRegistrar} 里的**全部 9 个**工具
 * （8 个只读查询 + 1 个写操作 {@code approve_course_apply}）。
 * 手写实现**保留不动**，由 {@code DeclarativeToolRegistrar} 在启动完成后覆盖注册
 * （计划 1.4：旧实现留一个版本周期作回归对照；回退只需注释掉注册那一行）。
 *
 * <p><b>必须守住的契约</b>（迁移只换"怎么写"，不换"模型看到什么、能不能直接执行"）：
 * <ol>
 *   <li>工具名、{@code displayName}、{@code riskLevel} 与手写版**逐字一致**。
 *       尤其 {@code approve_course_apply} 必须保持 {@code DANGEROUS}——
 *       标成 READ_ONLY 会让审批**绕过确认卡片**直接执行，这是本项目最不能出的错；</li>
 *   <li>参数 Schema 的**形状**（property 名 / 类型 / {@code required}）与手写版一致：</li>
 *   <li>返回值字段结构与手写版一致，**包括 50 条上限**这种业务约束；</li>
 *   <li>调用者身份（审批人）只经 {@link ToolContext} 传入，绝不进参数——否则模型能"替别人审批"。</li>
 * </ol>
 *
 * <p>⚠️ <b>参数写成平铺的 {@code @ToolParam} 形参</b>，不要用单个 DTO record：
 * 框架的 {@code JsonSchemaGenerator} 对"单个对象参数"会再套一层参数名
 * （模型得写成 {@code {"request":{...}}}），静默改变模型要填的形状。踩坑记录见开发记录 (二十一)。
 *
 * <p>⚠️ <b>两处框架表达不了、因而"缩水"的约束</b>（已知差异，不是疏忽）：
 * <ul>
 *   <li>{@code list_users.role} 的 {@code enum: [admin,teacher,student]}：{@code @ToolParam}
 *       只有 description/required 两个属性，生成不出 enum。替代做法是把可选值**写进描述文字**
 *       让模型照样知道取值范围；行为上仍与手写版一致（非法的值不会报错，只是查不到人）；</li>
 *   <li>{@code approve_course_apply.applyId} 的 {@code minimum: 1}：同理生成不出来。
 *       替代做法是在方法体里保留与手写版相同的**运行时范围校验**，
 *       因此"小于 1 的 id 不会被放行"这一行为没有退化。</li>
 * </ul>
 *
 * @author duyell
 */
@Component
@RequiredArgsConstructor
public class AdminDeclarativeTools implements DeclarativeToolGroup {

    /**
     * 查询类工具统一的返回条数上限（与手写实现保持一致）。
     *
     * <p>⚠️ 手写版里有一个 {@code private static final RiskLevel READ_ONLY = RiskLevel.READ_ONLY;}
     * 的别名，能少打几个字；**声明式版不能用它**——注解的属性值必须是**编译期常量**
     * （枚举常量本身），写别名会直接编译失败：{@code 枚举注释值必须是枚举常量}。
     * 因此下面每个 {@code @ToolMeta} 都完整写出 {@code RiskLevel.READ_ONLY}。
     */
    private static final int MAX_ROWS = 50;

    /** {@code list_users.role} 的合法取值：框架的 @ToolParam 表达不了 enum，只能写进描述 */
    private static final String ROLE_CHOICES = "admin（管理员）/ teacher（教师）/ student（学生）";

    private final SysUserMapper sysUserMapper;
    private final StudentMapper studentMapper;
    private final TeacherMapper teacherMapper;
    private final CourseMapper courseMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final ClazzMapper clazzMapper;
    private final HomeService homeService;
    private final CourseApplyService courseApplyService;
    private final ObjectMapper objectMapper;

    @Override
    public String role() {
        return "admin";
    }

    // ==================================================================================
    // 只读查询（8 个）
    // ==================================================================================

    @Tool(name = "get_statistics",
            description = "获取系统统计数据（学生数、教师数、课程数、班级数）。"
                    + "管理员问\"系统里有多少学生/教师/课程/班级\"\"整体数据情况\"时使用；不需要任何参数。"
                    + "需要具体名单（谁、哪门课）时改用 list_students / list_teachers / list_courses。")
    @ToolMeta(displayName = "系统统计", riskLevel = RiskLevel.READ_ONLY)
    public String getStatistics() throws Exception {
        Map<String, Object> stats = homeService.getStatistics();
        return objectMapper.writeValueAsString(stats);
    }

    /**
     * 用户列表。
     *
     * <p>⚠️ {@code role} 的 enum 约束见类注释：手写版的 Schema 里带
     * {@code enum:[admin,teacher,student]}，框架生成不出来，因此把可选值写进描述。
     * 行为上仍与手写版一致：不校验 role（非法值只是查不到人，不会报错）。
     */
    @Tool(name = "list_users",
            description = "查询系统用户列表（sys_user 表，含用户名、角色、邮箱、手机号）。"
                    + "管理员问\"有哪些账号\"\"系统用户\"\"某个角色的用户\"时使用。"
                    + "role 可选值为 " + ROLE_CHOICES + "；role 与 username 都可以省略，"
                    + "省略即不筛选、返回全部用户（最多 " + MAX_ROWS + " 条）——**不要为可选参数反问用户**，"
                    + "直接按默认值查询即可。")
    @ToolMeta(displayName = "用户列表", riskLevel = RiskLevel.READ_ONLY)
    public String listUsers(
            @ToolParam(description = "按角色筛选，可选值：" + ROLE_CHOICES + "；省略则不限制角色", required = false)
            @ParamConstraint(options = {"admin", "teacher", "student"})
            String role,
            @ToolParam(description = "用户名关键词（模糊匹配）；省略则不限制", required = false)
            String username) throws Exception {
        List<SysUser> users = sysUserMapper.list(role, username);
        if (users.size() > MAX_ROWS) {
            users = users.subList(0, MAX_ROWS);
        }
        return objectMapper.writeValueAsString(users);
    }

    @Tool(name = "list_students",
            description = "查询学生列表（含姓名、学号、班级、专业、学院等）。"
                    + "管理员问\"有哪些学生\"\"查一下这个学生\"时使用。"
                    + "studentName 与 studentId 都是关键词（模糊匹配）且可省略，"
                    + "省略即不筛选、返回全部学生（最多 " + MAX_ROWS + " 条）——**不要为可选参数反问用户**。")
    @ToolMeta(displayName = "学生列表", riskLevel = RiskLevel.READ_ONLY)
    public String listStudents(
            @ToolParam(description = "学生姓名关键词（模糊匹配）；省略则不限制", required = false)
            String studentName,
            @ToolParam(description = "学生学号关键词（模糊匹配）；省略则不限制", required = false)
            String studentId) throws Exception {
        List<Student> students = studentMapper.list(studentName, studentId, null, null, null);
        if (students.size() > MAX_ROWS) {
            students = students.subList(0, MAX_ROWS);
        }
        return objectMapper.writeValueAsString(students);
    }

    @Tool(name = "list_teachers",
            description = "查询教师列表（含姓名、工号、学院、职称等）。"
                    + "管理员问\"有哪些教师\"\"查一下这位老师\"时使用。"
                    + "teacherName 与 teacherId 都是关键词（模糊匹配）且可省略，"
                    + "省略即不筛选、返回全部教师（最多 " + MAX_ROWS + " 条）——**不要为可选参数反问用户**。")
    @ToolMeta(displayName = "教师列表", riskLevel = RiskLevel.READ_ONLY)
    public String listTeachers(
            @ToolParam(description = "教师姓名关键词（模糊匹配）；省略则不限制", required = false)
            String teacherName,
            @ToolParam(description = "教师工号关键词（模糊匹配）；省略则不限制", required = false)
            String teacherId) throws Exception {
        List<Teacher> teachers = teacherMapper.list(teacherName, teacherId, null, null);
        if (teachers.size() > MAX_ROWS) {
            teachers = teachers.subList(0, MAX_ROWS);
        }
        return objectMapper.writeValueAsString(teachers);
    }

    @Tool(name = "list_courses",
            description = "查询课程列表（含课程代码、课程名、授课教师、学期、学分等）。"
                    + "管理员问\"有哪些课程\"\"查一下这门课\"时使用。"
                    + "courseName 是关键词（模糊匹配）且可省略，省略即返回全部课程（最多 " + MAX_ROWS + " 条）——"
                    + "**不要为可选参数反问用户**。"
                    + "注意：本工具查的是**课程库**，不是开课申请（申请由 approve_course_apply 处理）。")
    @ToolMeta(displayName = "课程列表", riskLevel = RiskLevel.READ_ONLY)
    public String listCourses(
            @ToolParam(description = "课程名称关键词（模糊匹配）；省略则返回全部", required = false)
            String courseName) throws Exception {
        List<Course> courses = courseMapper.list(courseName, null, null, null, null, null, null);
        if (courses.size() > MAX_ROWS) {
            courses = courses.subList(0, MAX_ROWS);
        }
        return objectMapper.writeValueAsString(courses);
    }

    @Tool(name = "list_colleges",
            description = "查询所有学院列表（含学院名称）。"
                    + "管理员问\"有哪些学院\"时使用；不需要任何参数（学院数量少，不做筛选也不分页）。")
    @ToolMeta(displayName = "学院列表", riskLevel = RiskLevel.READ_ONLY)
    public String listColleges() throws Exception {
        List<College> colleges = collegeMapper.list(null);
        return objectMapper.writeValueAsString(colleges);
    }

    @Tool(name = "list_majors",
            description = "查询所有专业列表（含专业名称、所属学院）。"
                    + "管理员问\"有哪些专业\"时使用；不需要任何参数。"
                    + "若只想知道学院，用 list_colleges。")
    @ToolMeta(displayName = "专业列表", riskLevel = RiskLevel.READ_ONLY)
    public String listMajors() throws Exception {
        List<Major> majors = majorMapper.list(null, null);
        return objectMapper.writeValueAsString(majors);
    }

    @Tool(name = "list_classes",
            description = "查询所有班级列表（含班级名称、年级、所属专业与学院）。"
                    + "管理员问\"有哪些班级\"时使用；不需要任何参数。")
    @ToolMeta(displayName = "班级列表", riskLevel = RiskLevel.READ_ONLY)
    public String listClasses() throws Exception {
        List<Clazz> classes = clazzMapper.list(null, null, null, null);
        return objectMapper.writeValueAsString(classes);
    }

    // ==================================================================================
    // 写操作（1 个）：开课申请审批 —— 必须人工确认
    // ==================================================================================

    /**
     * 审批通过开课申请（写操作：会生成课程记录）。
     *
     * <p><b>为什么显式声明 {@link ToolContext}</b>：审批人必须是当前登录账号
     * （{@code approve(applyId, reviewer)} 的第二个参数），而它**绝不能来自参数**——
     * 一旦模型能填审批人，就能伪造审批痕迹。因此走 ToolContext（由调用链填充，模型看不到）。
     *
     * <p>描述里刻意写清"因此你该怎么办"（直接调用，不要用文字反问是否确认）：
     * 声明式迁移时踩过——描述只讲"系统会弹确认卡片"会让模型理解为"我得先征求同意"，
     * 于是在文字里反问而不调用工具（真机复现，见开发记录 (二十二)）。
     */
    @Tool(name = "approve_course_apply",
            description = "管理员审批通过一条**待审批（PENDING）**的开课申请，通过后系统会据申请生成课程记录。"
                    + "当管理员说\"通过这条开课申请\"\"批准 XXX 老师开课\"时，**直接调用本工具**："
                    + "审批前的确认由系统弹出的确认卡片负责，你不需要在文字里再问一次\"是否确认\"。"
                    + "参数：applyId（开课申请ID，必填；来自开课申请列表，或由教师提供）。"
                    + "只会通过 PENDING 的申请——已通过或已驳回的会被拒绝（不会重复生成课程）。"
                    + "审批通过**不等于**学生马上能选到：该课程还需要教师申请排课，"
                    + "且需要管理员开启选课轮次后学生才能选。"
                    + "审批人一律取当前登录账号，不接受指定。"
                    + "缺少 applyId 时返回 {\"error\":\"缺少参数 applyId\"}，此时请向管理员询问要审批的申请编号。")
    @ToolMeta(displayName = "审批通过开课申请", riskLevel = RiskLevel.DANGEROUS)
    public String approveCourseApply(
            @ToolParam(description = "开课申请ID（必填，且该申请需处于待审批状态）", required = true)
            @ParamConstraint(min = 1)
            Integer applyId,
            ToolContext context) throws Exception {
        String reviewer = DeclarativeToolContext.currentUserId(context);

        // 与手写版一致的运行时校验（Schema 里已用 @ParamConstraint(min=1) 声明，这里是第二道）
        if (applyId == null) {
            return errorJson("缺少参数 applyId");
        }
        if (applyId < 1) {
            return errorJson("applyId 必须在 1~" + Integer.MAX_VALUE + " 之间，实际为 " + applyId);
        }

        try {
            CourseApply approved = courseApplyService.approve(applyId, reviewer);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", approved.getId());
            result.put("courseCode", approved.getCourseCode());
            result.put("courseName", approved.getCourseName());
            result.put("term", approved.getTerm());
            result.put("status", approved.getStatus());
            result.put("statusText", "已通过");
            // 生成的课程行 id：后续申请排课、以及学生选课都围绕它进行
            result.put("createdCourseId", approved.getCreatedCourseId());
            result.put("message", "开课申请已通过，已生成课程（courseId=" + approved.getCreatedCourseId()
                    + "，课程代码 " + approved.getCourseCode() + "）。"
                    + "该课程还需教师申请排课，且需开启选课轮次后学生才能选到。");
            return objectMapper.writeValueAsString(result);
        } catch (BusinessException e) {
            // 业务失败也返回 JSON（而不是抛异常）：抛异常会被上层替换成模型无法利用的通用错误
            return errorJson(e.getMessage());
        }
    }

    /** 业务失败也返回 JSON（而不是抛异常），与手写版 {@code errorJson} 行为一致 */
    private String errorJson(String message) throws Exception {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message == null || message.isBlank() ? "操作失败" : message);
        return objectMapper.writeValueAsString(error);
    }
}
