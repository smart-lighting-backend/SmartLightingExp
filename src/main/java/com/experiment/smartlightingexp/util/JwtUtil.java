package com.experiment.smartlightingexp.util;

import com.experiment.smartlightingexp.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 — token 的生成、解析、校验。
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtUtil(JwtProperties jwtProperties) {
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expiration = jwtProperties.getExpiration();
    }

    /**
     * 生成 token（默认使用 subject 标识用户身份，如用户名或用户ID）
     */
    public String generateToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 从 token 中提取用户标识（subject）
     */
    public String extractSubject(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从 token 中提取过期时间
     */
    public Date extractExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    /**
     * 校验 token 是否有效（签名正确且未过期）
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
