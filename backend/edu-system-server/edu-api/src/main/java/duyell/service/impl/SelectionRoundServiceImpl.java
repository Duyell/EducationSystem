package duyell.service.impl;

import com.duyell.SelectionRound;
import com.duyell.SelectionRoundScope;
import com.duyell.Student;
import duyell.mapper.SelectionRoundMapper;
import duyell.mapper.SelectionRoundScopeMapper;
import duyell.mapper.StudentMapper;
import duyell.service.SelectionRoundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import utils.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SelectionRoundService} 实现。
 *
 * <p>核心是 {@link #evaluate}：把"能不能选/能不能退 + 为什么"算在一处，
 * {@code statusFor} 与 {@code requireCan*} 都走它——避免"页面说能选、提交却说不能"
 * 这种两套判断不一致的经典毛病。
 *
 * @author duyell
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelectionRoundServiceImpl implements SelectionRoundService {

    private final SelectionRoundMapper selectionRoundMapper;
    private final SelectionRoundScopeMapper scopeMapper;
    private final StudentMapper studentMapper;

    // ==================== 管理员 ====================

    @Override
    public List<SelectionRound> list(String term, Integer status) {
        List<SelectionRound> rounds = selectionRoundMapper.list(term, status);
        attachScopes(rounds);
        return rounds;
    }

    @Override
    public SelectionRound get(Integer id) {
        if (id == null) {
            return null;
        }
        SelectionRound round = selectionRoundMapper.selectById(id);
        if (round != null) {
            round.setScopes(scopeMapper.listByRound(id));
        }
        return round;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SelectionRound create(SelectionRound round) {
        validate(round);
        if (selectionRoundMapper.countByTermAndName(round.getTerm(), round.getRoundName()) > 0) {
            throw new BusinessException("该学期已存在同名轮次：" + round.getRoundName());
        }
        // 新建默认关闭：管理员应当显式"开启选课"，避免建完就悄悄放学生进来
        if (round.getStatus() == null) {
            round.setStatus(SelectionRound.STATUS_CLOSED);
        }
        selectionRoundMapper.add(round);

        if (round.getScopes() != null) {
            for (SelectionRoundScope scope : round.getScopes()) {
                scope.setRoundId(round.getId());
                addScope(scope);
            }
        }
        log.info("新建选课轮次: id={}, name={}, term={}, status={}",
                round.getId(), round.getRoundName(), round.getTerm(), round.getStatus());
        return get(round.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SelectionRound round) {
        if (round.getId() == null) {
            throw new BusinessException("缺少轮次 id");
        }
        SelectionRound existing = selectionRoundMapper.selectById(round.getId());
        if (existing == null) {
            throw new BusinessException("选课轮次不存在：" + round.getId());
        }
        validate(round);
        selectionRoundMapper.update(round);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Integer id) {
        if (id == null) {
            throw new BusinessException("缺少轮次 id");
        }
        // 先删范围再删轮次：轮次没了范围就成了孤儿数据
        scopeMapper.deleteByRound(id);
        selectionRoundMapper.deleteById(id);
        log.info("删除选课轮次: id={}（含其适用范围）", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setStatus(Integer id, Integer status) {
        if (id == null) {
            throw new BusinessException("缺少轮次 id");
        }
        if (status == null
                || (status != SelectionRound.STATUS_OPEN && status != SelectionRound.STATUS_CLOSED)) {
            throw new BusinessException("状态只能是 1（开启）或 0（关闭）");
        }
        SelectionRound round = selectionRoundMapper.selectById(id);
        if (round == null) {
            throw new BusinessException("选课轮次不存在：" + id);
        }
        selectionRoundMapper.updateStatus(id, status);
        log.info("选课轮次 {} 状态改为 {}", id, status == SelectionRound.STATUS_OPEN ? "开启" : "关闭");
    }

    @Override
    public List<SelectionRoundScope> listScopes(Integer roundId) {
        return roundId == null ? List.of() : scopeMapper.listByRound(roundId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SelectionRoundScope addScope(SelectionRoundScope scope) {
        if (scope == null || scope.getRoundId() == null) {
            throw new BusinessException("缺少轮次 id");
        }
        if (selectionRoundMapper.selectById(scope.getRoundId()) == null) {
            throw new BusinessException("选课轮次不存在：" + scope.getRoundId());
        }
        // 三个条件全空的"范围"等于没限制，写进来只会让人误解，直接拒绝
        if (scope.isUnrestricted()) {
            throw new BusinessException("适用范围至少要填年级、专业、学院中的一项（都不填＝不限，无需添加）");
        }
        scopeMapper.add(scope);
        return scope;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeScope(Integer scopeId) {
        if (scopeId == null) {
            throw new BusinessException("缺少范围 id");
        }
        scopeMapper.deleteById(scopeId);
    }

    // ==================== 学生侧 ====================

    @Override
    public SelectionStatus statusFor(String studentId, String term) {
        return evaluate(studentId, term);
    }

    @Override
    public SelectionRound requireCanSelect(String studentId, String term) {
        SelectionStatus status = evaluate(studentId, term);
        if (!status.canSelect()) {
            throw new BusinessException(status.reason() == null ? "当前不在选课开放时间内" : status.reason());
        }
        return selectionRoundMapper.selectById(status.roundId());
    }

    @Override
    public SelectionRound requireCanDrop(String studentId, String term) {
        SelectionStatus status = evaluate(studentId, term);
        if (!status.canDrop()) {
            throw new BusinessException(status.reason() == null ? "当前不在可退课时间内" : status.reason());
        }
        return selectionRoundMapper.selectById(status.roundId());
    }

    /**
     * 算出该生在该学期的选/退状态与原因。
     *
     * <p>判定顺序刻意"先开关、后范围、最后时间窗"，这样报出来的原因最贴近管理员的直觉：
     * 没开启就直说没开启，而不是含糊地说"不在选课时间内"。
     */
    private SelectionStatus evaluate(String studentId, String term) {
        if (term == null || term.isBlank()) {
            return closed(term, "未指定学期，无法判断选课是否开放");
        }
        Student placement = studentMapper.selectWithGradeAndMajor(studentId);
        if (placement == null) {
            return closed(term, "学生不存在，无法判断选课范围");
        }

        List<SelectionRound> openRounds = selectionRoundMapper.selectOpenByTerm(term);
        if (openRounds.isEmpty()) {
            return closed(term, "该学期没有已开启的选课轮次（选课未开放，只能查看）");
        }

        LocalDateTime now = LocalDateTime.now();
        // 在范围内的开启轮次
        List<SelectionRound> scoped = new ArrayList<>();
        for (SelectionRound round : openRounds) {
            if (inScope(round, placement)) {
                scoped.add(round);
            }
        }
        if (scoped.isEmpty()) {
            return closed(term, "你所在的年级/专业/学院不在本次选课的适用范围内");
        }

        SelectionRound selectable = null;
        SelectionRound droppable = null;
        for (SelectionRound round : scoped) {
            if (selectable == null && inWindow(now, round.getSelectStart(), round.getSelectEnd())) {
                selectable = round;
            }
            // 选课期间可退；否则要等补退选期间 → 两个窗口任一命中即可退
            if (droppable == null
                    && (inWindow(now, round.getSelectStart(), round.getSelectEnd())
                        || inWindow(now, round.getDropStart(), round.getDropEnd()))) {
                droppable = round;
            }
        }

        SelectionRound anchor = selectable != null ? selectable : droppable;
        if (anchor == null) {
            return closed(term, "选课时间未开始或已结束（不在选课/补退选时间窗内）");
        }
        String reason = buildReason(selectable, droppable);
        return new SelectionStatus(true, selectable != null, droppable != null,
                anchor.getId(), anchor.getRoundName(), term, anchor.getMaxCredits(), reason);
    }

    private static String buildReason(SelectionRound selectable, SelectionRound droppable) {
        if (selectable != null && droppable != null) {
            return "选课开放中，可以选课也可以退课";
        }
        if (selectable != null) {
            return "选课开放中";
        }
        return "选课已结束，当前处于补退选期间，只能退课";
    }

    private static SelectionStatus closed(String term, String reason) {
        return new SelectionStatus(false, false, false, null, null, term, null, reason);
    }

    /** 学生是否落在该轮次的适用范围内：无范围记录 = 不限 */
    private boolean inScope(SelectionRound round, Student placement) {
        List<SelectionRoundScope> scopes = scopeMapper.listByRound(round.getId());
        if (scopes.isEmpty()) {
            return true;
        }
        for (SelectionRoundScope scope : scopes) {
            if (scope.matches(placement.getGrade(), placement.getMajorId(), placement.getCollegeId())) {
                return true;
            }
        }
        return false;
    }

    /** 闭区间：端点时刻算在窗口内；窗口为空视为不开放 */
    private static boolean inWindow(LocalDateTime now, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return false;
        }
        return !now.isBefore(start) && !now.isAfter(end);
    }

    private void validate(SelectionRound round) {
        if (round == null) {
            throw new BusinessException("轮次内容不能为空");
        }
        if (round.getRoundName() == null || round.getRoundName().isBlank()) {
            throw new BusinessException("轮次名称不能为空");
        }
        if (round.getTerm() == null || round.getTerm().isBlank()) {
            throw new BusinessException("必须指定适用学期");
        }
        if (round.getSelectStart() == null || round.getSelectEnd() == null) {
            throw new BusinessException("必须给出选课开放的开始与结束时间");
        }
        if (round.getSelectStart().isAfter(round.getSelectEnd())) {
            throw new BusinessException("选课开始时间不能晚于结束时间");
        }
        if (round.getDropStart() != null && round.getDropEnd() != null
                && round.getDropStart().isAfter(round.getDropEnd())) {
            throw new BusinessException("补退选开始时间不能晚于结束时间");
        }
        if ((round.getDropStart() == null) != (round.getDropEnd() == null)) {
            throw new BusinessException("补退选的开始与结束时间必须同时填写或同时留空");
        }
        if (round.getMaxCredits() != null && round.getMaxCredits().signum() < 0) {
            throw new BusinessException("学分上限不能为负");
        }
    }

    /** 列表页一次性装配范围，避免逐行查库 */
    private void attachScopes(List<SelectionRound> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<>(rounds.size());
        for (SelectionRound round : rounds) {
            ids.add(round.getId());
        }
        List<SelectionRoundScope> all = scopeMapper.listByRounds(ids);
        for (SelectionRound round : rounds) {
            List<SelectionRoundScope> mine = new ArrayList<>();
            for (SelectionRoundScope scope : all) {
                if (scope.getRoundId().equals(round.getId())) {
                    mine.add(scope);
                }
            }
            round.setScopes(mine);
        }
    }
}
