package com.duyell;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


/**
 * @author 53473
 * 登录用户
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysUser {
    /**
     * username: 登录账号（学号/工号/admin）
     * role: admin/teacher/student
     * Status: 1正常 0禁用
     */
    private Integer id;
    private String username;
    private String password;
    private String email;
    private String phone;
    private String role;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
