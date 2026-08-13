package utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author duyell
 * JWT 工具（jjwt 0.13 API）：密钥与过期时间从配置读取（application.yml 中通过环境变量 JWT_SECRET 注入，生产环境必须设置）
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    /** Token 有效期（毫秒），与 Redis 中 token 的 TTL 保持一致 */
    private final long expirationMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration-minutes:30}") long expirationMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 要求密钥至少 256 位（32 字节）
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret 长度不足 32 字节，无法用于 HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMillis = expirationMinutes * 60 * 1000L;
    }

    /** Token 有效期（毫秒），供 Redis 存储 TTL 对齐使用 */
    public long getExpirationMillis() {
        return expirationMillis;
    }

    /**
     * 生成Token
     * @param username 用户名
     * @param role     角色
     */
    public String generateToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMillis))
                .signWith(key)
                .compact();
    }

    /** 解析Token，获取Claims
     * @param token token对象
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从Token中获取用户名*/
    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    /** 从Token中获取角色*/
    public String getRoleFromToken(String token) {
        return parseToken(token).get("role", String.class);
    }

    /**校验Token是否过期 */
    public boolean isTokenExpired(String token) {
        return parseToken(token).getExpiration().before(new Date());
    }
}
