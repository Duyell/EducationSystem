package duyell.controller;

import duyell.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import login.LoginReqDTO;
import login.LoginRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author duyell
 */
@RestController
@RequiredArgsConstructor
@RequestMapping
public class LoginController {

    private final LoginService sysUserService;

    @PostMapping("/login")
    public LoginRespDTO login(@RequestBody LoginReqDTO loginReqDTO) {
        return  sysUserService.login(loginReqDTO);
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        sysUserService.logout(request);
        return "登出成功";
    }
}
