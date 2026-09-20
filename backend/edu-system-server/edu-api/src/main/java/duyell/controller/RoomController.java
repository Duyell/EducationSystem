package duyell.controller;

import com.duyell.Room;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import duyell.mapper.RoomMapper;
import duyell.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import utils.PageResult;
import utils.Result;

import java.util.List;

/**
 * 教室接口（权限见 {@code LoginInterceptor}，见 docs/教务业务扩展设计.md §4.4）。
 *
 * <p>教室本身是纯 CRUD（没有业务规则），故这里直接用 {@link RoomMapper}，
 * 不为它套一层 1:1 的空服务；**与时间相关的逻辑**（空闲判定、冲突、推荐）
 * 一律走 {@link ScheduleService}，那才是真正需要集中管理规则的地方。
 *
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/room")
public class RoomController {

    private final RoomMapper roomMapper;
    private final ScheduleService scheduleService;

    /** 教室列表（管理员，支持楼栋/类型/容量/状态筛选） */
    @GetMapping
    public Result<PageResult<Room>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "20") Integer pageSize,
                                         @RequestParam(required = false) String building,
                                         @RequestParam(required = false) String roomType,
                                         @RequestParam(required = false) Integer minCapacity,
                                         @RequestParam(required = false) Integer status) {
        Page<Room> page = PageHelper.startPage(pageNum, pageSize);
        List<Room> list = roomMapper.list(building, roomType, minCapacity, status);
        return Result.success(new PageResult<>(page.getTotal(), list));
    }

    /** 教室详情（管理员） */
    @GetMapping("/{id}")
    public Result<Room> detail(@PathVariable Integer id) {
        Room room = roomMapper.selectById(id);
        return room == null ? Result.error("404", "教室不存在") : Result.success(room);
    }

    /**
     * 某时段空闲的教室（教师与管理员）。
     *
     * <p>排课页选教室时用；与审批环节用的是**同一套重叠判据**，不会出现"页面显示空闲、审批说冲突"。
     */
    @GetMapping("/free")
    public Result<ScheduleService.RoomRecommendation> free(@RequestParam String term,
                                                          @RequestParam Integer weekday,
                                                          @RequestParam Integer startPeriod,
                                                          @RequestParam Integer endPeriod,
                                                          @RequestParam Integer startWeek,
                                                          @RequestParam Integer endWeek,
                                                          @RequestParam(required = false) Integer minCapacity,
                                                          @RequestParam(required = false) Integer excludeCourseId,
                                                          @RequestParam(defaultValue = "10") Integer limit) {
        return Result.success(scheduleService.recommendRoom(term, weekday, startPeriod, endPeriod,
                startWeek, endWeek, minCapacity, excludeCourseId, limit));
    }

    /** 新增教室（管理员） */
    @PostMapping
    public Result<String> add(@RequestBody Room room) {
        if (room.getStatus() == null) {
            room.setStatus(1);
        }
        roomMapper.add(room);
        return Result.success("添加成功");
    }

    /** 修改教室（管理员） */
    @PutMapping
    public Result<String> update(@RequestBody Room room) {
        if (room.getId() == null) {
            return Result.error("400", "缺少教室 id");
        }
        roomMapper.update(room);
        return Result.success("更新成功");
    }

    /** 删除教室（管理员） */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {
        roomMapper.deleteById(id);
        return Result.success("删除成功");
    }
}
