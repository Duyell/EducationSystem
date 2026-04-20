package duyell.service;

import com.duyell.College;
import utils.PageResult;

/**
 * @author duyell
 */
public interface CollegeService {
    /**
     * 获取学院列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 学院列表
     */
    PageResult<College> page(Integer pageNum, Integer pageSize);

    /**
     * 添加学院
     * @param college 学院
     */
    void add(College college);

    /**
     * 删除学院
     * @param collegeId 学院id
     */
    void delete(Integer collegeId);

    /**
     * 修改学院信息
     * @param college 学院
     */
    void update(College college);
}
