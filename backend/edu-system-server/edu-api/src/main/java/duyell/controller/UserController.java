package duyell.controller;

import com.duyell.SysUser;
import duyell.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import utils.PageResult;
import utils.Result;

/**
 * @author duyell
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/user")
public class UserController {
    private final SysUserService sysUserService;

    @GetMapping
    public Result<PageResult<SysUser>> page(@RequestParam(defaultValue = "1") Integer page,
                       @RequestParam(defaultValue = "10") Integer pageSize) {
        PageResult<SysUser> pageResult = sysUserService.page(page, pageSize);
        return Result.success(pageResult);
    }

    @PostMapping
    public Result<String> add(@RequestBody SysUser sysUser) {
        sysUserService.add(sysUser);
        return Result.success("添加成功");
    }

    @DeleteMapping
    public Result<String> delete(@RequestParam String username) {
    	try{
            sysUserService.delete(username);
            return Result.success("删除成功");
        }catch (Exception e){
            return Result.error("400", e.getMessage());
        }

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
}
