package duyell.service;

import com.duyell.SelectionRound;
import com.duyell.SelectionRoundScope;

import java.math.BigDecimal;
import java.util.List;

/**
 * 选课轮次服务：管理员控制开关与适用范围，学生侧回答"现在能不能选/能不能退"。
 *
 * <p>用户明确的规则（docs/教务业务扩展设计.md §2，不得改动）：
 * <ul>
 *   <li>只有管理员**开启选课**后学生才能选；否则**只能看**</li>
 *   <li>选课持续期间**可以退**，否则要等**补退选**期间才能退</li>
 * </ul>
 *
 * <p>所以"能不能选"是三个条件的合取：
 * {@code status=1}（管理员开了开关） ∧ 学生在轮次适用范围内 ∧ 当前时间落在选课窗口内。
 * 少判任何一个都会放行不该放行的选课。
 *
 * @author duyell
 */
public interface SelectionRoundService {

    /**
     * 学生对某学期的选退状态。
     *
     * <p>不抛异常，专门给前端展示「未开放／可查看／可退课」用；
     * {@code reason} 是可以直接显示给用户的中文说明。
     *
     * <p>⚠️ 这里**不要**再加 `readOnly()` 之类的派生方法：Jackson 只序列化 record 组件，
     * 派生方法**不在报文里**（P1 的 `AuditResult.satisfied()` 就因此让前端永远走不到成功分支）。
     * 需要"是否只读"请在调用方用 {@code !canSelect && !canDrop} 自行推导。
     */
    record SelectionStatus(boolean roundOpen,
                           boolean canSelect,
                           boolean canDrop,
                           Integer roundId,
                           String roundName,
                           String term,
                           BigDecimal maxCredits,
                           String reason) {
    }

    // ---------- 管理员 ----------

    List<SelectionRound> list(String term, Integer status);

    SelectionRound get(Integer id);

    /** 新建轮次（校验时间窗顺序；带上 scopes 可一次配好范围） */
    SelectionRound create(SelectionRound round);

    void update(SelectionRound round);

    /** 删除轮次（连带删除其适用范围） */
    void remove(Integer id);

    /** 开关：1=开启 0=关闭 */
    void setStatus(Integer id, Integer status);

    List<SelectionRoundScope> listScopes(Integer roundId);

    SelectionRoundScope addScope(SelectionRoundScope scope);

    void removeScope(Integer scopeId);

    // ---------- 学生侧 ----------

    /** 只查询不抛异常，供页面展示状态 */
    SelectionStatus statusFor(String studentId, String term);

    /**
     * 校验"现在可以选这门学期的课"，返回命中的轮次（用于把 round_id 记进选课记录）。
     *
     * @throws utils.BusinessException 不能选时，异常信息即原因
     */
    SelectionRound requireCanSelect(String studentId, String term);

    /**
     * 校验"现在可以退这门学期的课"，返回命中的轮次。
     *
     * @throws utils.BusinessException 不能退时，异常信息即原因
     */
    SelectionRound requireCanDrop(String studentId, String term);
}
