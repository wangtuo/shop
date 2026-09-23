package com.shop.framework.security;

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
 * JWT 签发/校验。用户服务签发，网关校验，密钥由配置统一下发（生产环境走配置中心加密）。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${shop.jwt.secret:dev-local-only-jwt-secret-key-0123456789abcdef}") String secret,
                      @Value("${shop.jwt.ttl-hours:168}") long ttlHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = ttlHours * 3600_000L;
    }

    public String issue(Long userId, String userName, Integer userType, Long merchantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("name", userName);
        claims.put("utype", userType);
        if (merchantId != null) {
            claims.put("mid", merchantId);
        }
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
