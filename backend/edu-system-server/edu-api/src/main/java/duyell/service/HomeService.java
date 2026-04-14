package duyell.service;

import java.util.Map;


/**
 * @author duyell
 */
public interface HomeService {
    /**
     * 获取统计的学生和老师总数
     * @return 统计数据
     */
    Map<String, Object> getStatistics();
}

