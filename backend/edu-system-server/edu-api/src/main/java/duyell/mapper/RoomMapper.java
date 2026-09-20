package duyell.mapper;

import com.duyell.Room;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 教室 Mapper。
 *
 * @author duyell
 */
@Mapper
public interface RoomMapper {

    /** 按 id 查询 */
    @Select("select * from room where id = #{id}")
    Room selectById(@Param("id") Integer id);

    /**
     * 按展示名查询（如 教1-101）。
     *
     * <p>名字是 (楼栋, 楼层, 房间号) 的自然结果且有唯一约束，
     * 按名字查比按自增 id 查稳定——批量生成的 id 顺序不保证，新装库与现有库会不同。
     */
    @Select("select * from room where room_name = #{roomName}")
    Room selectByName(@Param("roomName") String roomName);

    /** 列表（各筛选条件均可空，动态 SQL 见 XML） */
    List<Room> list(@Param("building") String building,
                    @Param("roomType") String roomType,
                    @Param("minCapacity") Integer minCapacity,
                    @Param("status") Integer status);

    /** 新增（回填自增 id） */
    @Insert("""
            insert into room(building, floor_no, room_no, room_name, capacity, room_type, status)
            values(#{building}, #{floorNo}, #{roomNo}, #{roomName}, #{capacity}, #{roomType}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void add(Room room);

    /** 更新（动态 SQL 见 XML） */
    void update(Room room);

    /** 删除 */
    @Delete("delete from room where id = #{id}")
    void deleteById(@Param("id") Integer id);

    /** 按楼栋统计（用于快速核对 800 间的生成结果） */
    @Select("select count(*) from room where building = #{building}")
    int countByBuilding(@Param("building") String building);

    /**
     * 指定时段**空闲**且满足容量要求的教室，按容量升序（最贴近需求的在前）。
     *
     * <p>用 `not exists` 反连接一次查完，等价于「查占用 → 做差集」两步，
     * 但只需一次往返，且天然得到"容量最接近优先"的排序。
     *
     * <p>重叠判据与 {@code ClassTimeMapper.selectConflicts} 完全一致（闭区间）。
     *
     * @param term            学期（来自 course.term）
     * @param excludeCourseId 排除自己（改排课时不该和自己冲突），可空
     */
    List<Room> pickFreeRooms(@Param("term") String term,
                             @Param("weekday") Integer weekday,
                             @Param("startPeriod") Integer startPeriod,
                             @Param("endPeriod") Integer endPeriod,
                             @Param("startWeek") Integer startWeek,
                             @Param("endWeek") Integer endWeek,
                             @Param("minCapacity") Integer minCapacity,
                             @Param("roomType") String roomType,
                             @Param("excludeCourseId") Integer excludeCourseId,
                             @Param("limit") Integer limit);

    /** 容量够用的可用教室总数（与时段无关，用于区分"没有这么大的教室"和"该时段都占满了"） */
    @Select("select count(*) from room where status = 1 and capacity >= #{minCapacity}")
    int countUsableByCapacity(@Param("minCapacity") Integer minCapacity);
}
