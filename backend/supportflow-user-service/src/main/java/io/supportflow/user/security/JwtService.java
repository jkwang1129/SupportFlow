package io.supportflow.user.security;

import io.supportflow.common.api.ResultCode;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（HS256）。
 */
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long expireSeconds;

    public JwtService(
            @Value("${supportflow.auth.jwt-secret}") String secret,
            @Value("${supportflow.auth.jwt-expire-seconds:7200}") long expireSeconds
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    /**
     * 签发访问令牌。
     */
    public String createToken(LoginUser user) {
        Date now = new Date();
        Date expireAt = new Date(now.getTime() + expireSeconds * 1000L);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.userId()))
                .claim("uno", user.userNo())
                .claim("name", user.name())
                .claim("type", user.userType())
                .claim("tnt", user.tenantCode() == null ? "" : user.tenantCode())
                .issuedAt(now)
                .expiration(expireAt)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析令牌；非法/过期抛未认证异常。
     */
    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new LoginUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("uno", String.class),
                    claims.get("name", String.class),
                    claims.get("type", Integer.class),
                    claims.get("tnt", String.class)
            );
        } catch (Exception e) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }
}
