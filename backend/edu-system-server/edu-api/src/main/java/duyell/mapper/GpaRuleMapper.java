package duyell.mapper;

import com.duyell.GpaRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 绩点规则 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface GpaRuleMapper {

    /**
     * 取当前启用的绩点规则。
     *
     * <p>约定同时只有一条启用（设计文档 §3.2(3)）。
     * 若返回 null，说明规则未配置 —— 调用方应降级（不计算绩点）而不是猜测默认值。
     */
    @Select("select * from gpa_rule where status = 1 order by id limit 1")
    GpaRule selectEnabled();
}
