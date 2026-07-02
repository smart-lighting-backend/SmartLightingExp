package com.experiment.smartlightingexp.util;

import com.experiment.smartlightingexp.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * JWT 工具类 — token 的生成、解析、校验。
 * 支持存储 username、roleCode、permissions 等自定义 claims。
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtUtil(JwtProperties jwtProperties) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
        this.expiration = jwtProperties.getExpiration();
    }

    /**
     * 生成 token（含用户信息和权限）。
     *
     * @param username    用户名
     * @param roleCode    角色编码
     * @param permissions 权限编码列表
     * @return JWT token 字符串
     */
    public String generateToken(String username, String roleCode, List<String> permissions) {
        return Jwts.builder()
                .subject(username)
                .claim("roleCode", roleCode)
                .claim("permissions", permissions)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 从 token 中提取用户名（subject）。
     */
    public String extractSubject(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从 token 中提取角色编码。
     */
    public String extractRoleCode(String token) {
        return parseClaims(token).get("roleCode", String.class);
    }

    /**
     * 从 token 中提取权限编码列表。
     */
    @SuppressWarnings("unchecked")
    public List<String> extractPermissions(String token) {
        return parseClaims(token).get("permissions", List.class);
    }

    /**
     * 从 token 中提取所有 claims。
     */
    public Map<String, Object> extractAllClaims(String token) {
        return parseClaims(token);
    }

    /**
     * 从 token 中提取过期时间。
     */
    public Date extractExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    /**
     * 校验 token 是否有效（签名正确且未过期）。
     */
    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
