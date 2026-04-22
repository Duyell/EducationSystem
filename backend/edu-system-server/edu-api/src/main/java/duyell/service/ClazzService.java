package duyell.service;

import com.duyell.Clazz;
import utils.PageResult;

import java.util.List;

/**
 * @author duyell
 */
public interface ClazzService {
    /**
     * 获取班级列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param clazzName 班级名称
     * @param grade 年级
     * @param majorId 专业id
     * @param collegeId 学院id
     * @return 班级列表
     */
    PageResult<Clazz> page(Integer pageNum, Integer pageSize, String clazzName, String grade, Integer majorId, Integer collegeId);

    /**
     * 添加班级
     * @param clazz 班级
     */
    void add(Clazz clazz);

    /**
     * 删除班级
     * @param clazzId 班级id
     */
    void delete(Integer clazzId);

    /**
     * 修改班级信息
     * @param clazz 班级
     */
    void update(Clazz clazz);
}
