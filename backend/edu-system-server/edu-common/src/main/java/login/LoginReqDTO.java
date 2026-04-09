package login;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author duyell
 * 登录请求参数
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginReqDTO {
    private String username;
    private String password;
}
