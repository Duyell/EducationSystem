package duyell.service;

import com.duyell.Score;
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
}
