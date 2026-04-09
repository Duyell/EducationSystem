package utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author duyell
 */
@Component
public class JwtUtil {

    private static final String SECRET_KEY = "edu_system_secret_key_1234567890_abcdefg";
    // 过期时间：7天（单位：毫秒）
    private static final long EXPIRATION = 7 * 24 * 60 * 60 * 1000L;

    // 生成Token
    public String generateToken(String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        SecretKey key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes());
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 解析Token，获取Claims
    public Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET_KEY.getBytes());
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // 从Token中获取用户名
    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    // 从Token中获取角色
    public String getRoleFromToken(String token) {
        return parseToken(token).get("role", String.class);
    }

    // 校验Token是否过期
    public boolean isTokenExpired(String token) {
        return parseToken(token).getExpiration().before(new Date());
    }
}