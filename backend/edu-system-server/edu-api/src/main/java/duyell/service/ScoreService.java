package duyell.service;

import com.duyell.Score;
import com.duyell.ScoreChangeLog;
import utils.PageResult;

/**
 * @author duyell
 */
public interface ScoreService {
    /**
     * 获取成绩列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param studentId 学生id
     * @param courseId 课程id
     * @param term 学期
     * @return 成绩列表
     */
    PageResult<Score> page(Integer pageNum, Integer pageSize, Integer studentId, Integer courseId, String term);

    /**
     * 添加成绩
     * @param score 成绩
     */
    void add(Score score);

    /**
     * 删除成绩
     * @param scoreId 成绩id
     */
    void delete(Integer scoreId);

    /**
     * 修改成绩信息
     * @param score 成绩
     */
    void update(Score score);

    /**
     * 根据ID查询成绩
     * @param scoreId 成绩id
     * @return 成绩
     */
    Score selectById(Integer scoreId);

    /**
     * 成绩变更日志（谁、何时、改了哪门课哪个学生、改前改后、来自界面还是智能助手）。
     *
     * <p>制度依据 {@code docs/policies/09-教师成绩录入与修改规范.md} §4.5：
     * 成绩是可申诉数据，"改过"必须可追溯。三个筛选条件都可为空（＝不筛），按时间倒序。
     *
     * @param pageNum    页码
     * @param pageSize   每页大小
     * @param studentId  学号（可空）
     * @param courseId   课程 id（可空）
     * @param operatorId 操作人（可空）
     */
    PageResult<ScoreChangeLog> changeLog(Integer pageNum, Integer pageSize,
                                         String studentId, Integer courseId, String operatorId);
}
