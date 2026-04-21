package duyell.controller;

import com.duyell.SysUser;
import duyell.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

import java.util.Map;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/user")
public class UserController {
    private final SysUserService sysUserService;

    @GetMapping
    public Result<PageResult<SysUser>> page(
                        @RequestParam(defaultValue = "1") Integer page,
                        @RequestParam(defaultValue = "10") Integer pageSize,
                        @RequestParam(required = false) String role,
                        @RequestParam(required = false) String username) {
        PageResult<SysUser> pageResult = sysUserService.page(page, pageSize,role,username);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody SysUser sysUser) {
        sysUserService.add(sysUser);
        return Result.success("添加成功");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Integer id) {

        sysUserService.delete(id);
        return Result.success("删除成功");
    }

    @PutMapping
    public Result<String> update(@RequestBody SysUser sysUser) {
        try{
            sysUserService.update(sysUser);
            return Result.success("更新成功");
        }catch (RuntimeException e){
            return Result.error("400", e.getMessage());
        }catch (Exception e){
            return Result.error( "500","更新失败");
        }
    }
    /**
     * 更新用户状态
     */
    @PutMapping("/status/{id}")
    public Result<String> updateStatus(@PathVariable Integer id,
                                        @RequestBody Map<String,Integer> payload) {
        Integer status = payload.get("status");
        sysUserService.updateStatus(id,status);
        return Result.success("更新成功");
    }


}
