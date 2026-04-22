package duyell.service;

import com.duyell.Major;
import utils.PageResult;

/**
 * @author duyell
 */
public interface MajorService {
    /**
     * 获取专业列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param collegeId 学院id
     * @param majorName 专业名称
     * @return 专业列表
     */
    PageResult<Major> page(Integer pageNum, Integer pageSize, Integer collegeId, String majorName);

    /**
     * 添加专业
     * @param major 专业
     */
    void add(Major major);

    /**
     * 删除专业
     * @param majorId 专业id
     */
    void delete(Integer majorId);

    /**
     * 修改专业信息
     * @param major 专业
     */
    void update(Major major);
}
