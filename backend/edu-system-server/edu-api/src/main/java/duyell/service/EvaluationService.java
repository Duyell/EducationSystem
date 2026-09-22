package duyell.service;

import com.duyell.TeacherEvaluation;
import utils.PageResult;

import java.util.List;

/**
 * 教学评价（制度依据：{@code docs/policies/08-教学评价规则.md}）。
 *
 * <p>两条由作者在 2026-09-22 确认的规则，都落在这一层（而不是散在 controller 与 AI 工具里）：
 * <ol>
 *   <li><b>匿名</b>：教师能看评价内容，但**看不到是谁提交的** —— 所有面向教师的读取都必须走
 *       {@link #pageForTeacher} / {@link #listForTeacher}，不要绕过它们直接用 mapper，
 *       否则匿名会从"少改一处"的地方漏出去；</li>
 *   <li><b>归属校验</b>：只能评价**自己已选课程**的授课教师，且授课教师由**课程**决定
 *       （不接受调用方传入的 teacherId）。</li>
 * </ol>
 *
 * @author duyell
 */
public interface EvaluationService {

    /**
     * 提交评价。
     *
     * <p>会校验：学生是否选了这门课、课程是否存在、是否已经评价过；
     * 并把 {@code teacherId} 强制设为该课程的授课教师。
     *
     * @throws utils.BusinessException 校验不通过时抛出（原因可直接展示给学生）
     */
    void add(TeacherEvaluation evaluation);

    /** 通用分页查询（学生看自己的 / 管理员按条件查） */
    PageResult<TeacherEvaluation> page(Integer pageNum, Integer pageSize,
                                       Integer courseId, String studentId, String teacherId);

    /** 通用列表查询（学生看自己的 / 管理员按条件查） */
    List<TeacherEvaluation> list(Integer courseId, String studentId, String teacherId);

    /**
     * **教师视角**的分页查询：内容可见、**提交人匿名**。
     *
     * @param teacherId 当前登录教师工号（只返回他自己的评价）
     */
    PageResult<TeacherEvaluation> pageForTeacher(Integer pageNum, Integer pageSize, String teacherId);

    /**
     * **教师视角**的列表查询：内容可见、**提交人匿名**。
     *
     * @param teacherId 当前登录教师工号（只返回他自己的评价）
     */
    List<TeacherEvaluation> listForTeacher(String teacherId);

    /** 查询某课程是否已被该学生评价过 */
    TeacherEvaluation check(Integer courseId, String studentId);

    /**
     * 教师收到的所有评价的平均分（不受分页影响）
     * @param teacherId 教师工号
     * @return 平均分
     */
    Double avgScoreByTeacherId(String teacherId);
}
