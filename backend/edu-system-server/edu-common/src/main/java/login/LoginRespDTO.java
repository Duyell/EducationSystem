package login;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author 53473
 * 登录返回数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginRespDTO {
    private String token;
    private String role;
    private String username;
}
