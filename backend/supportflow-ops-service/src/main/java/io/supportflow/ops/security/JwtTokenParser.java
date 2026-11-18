package io.supportflow.ops.security;

import io.supportflow.common.api.ResultCode;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/** JWT 解析（与 user-service 共用同一签名密钥），审计日志页要据此判断是不是平台账号。 */
@Component
public class JwtTokenParser {

    private final SecretKey secretKey;

    public JwtTokenParser(@Value("${supportflow.auth.jwt-secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析令牌；非法 / 过期抛未认证异常 */
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
                    claims.get("type", Integer.class) == null ? 2 : claims.get("type", Integer.class),
                    claims.get("tnt", String.class));
        } catch (Exception e) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未认证或登录已过期");
        }
    }

    /** 从 Authorization 头里取令牌并解析 */
    public LoginUser requireLoginUser(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未认证或登录已过期");
        }
        String value = authorization.trim();
        return parseToken(value.regionMatches(true, 0, "Bearer ", 0, 7)
                ? value.substring(7).trim() : value);
    }
}
