package duyell.service;

import com.duyell.CourseApply;

import java.util.List;

/**
 * 开课申请服务（第一个审批流）。
 *
 * <p>用户明确的流程：
 * <pre>
 * 教师提交申请 → 管理员审批 → 审批通过才生效 → 且【下次开启选课】时学生才能选
 * </pre>
 * 「审批通过」与「可被选」是两个状态：审批通过后生成 {@code course} 行，
 * 但能否被学生选还要看是否处于某个已开启的选课轮次（P3 实现）。
 *
 * @author duyell
 */
public interface CourseApplyService {

    /**
     * 教师提交开课申请。
     *
     * <p>{@code teacherId} 由调用方（Controller，取 token）保证是当前登录教师。
     * 服务内校验：教师存在、字段完整、容量为正、同教师同学期同代码无未驳回的重复申请。
     */
    CourseApply submit(CourseApply apply);

    /** 我的申请（教师） */
    List<CourseApply> listMine(String teacherId);

    /** 全部申请（管理员；status/term 可空） */
    List<CourseApply> listAll(String status, String term);

    CourseApply get(Integer applyId);

    /**
     * 审批通过：据申请生成 {@code course} 行并回填 {@code created_course_id}。
     *
     * <p>只允许 PENDING → APPROVED，重复审批会被拒。
     */
    CourseApply approve(Integer applyId, String reviewer);

    /** 驳回：reason 必填（否则教师不知道为什么被拒） */
    void reject(Integer applyId, String reviewer, String reason);
}
