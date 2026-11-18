package io.supportflow.customer.security;

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

/**
 * JWT 解析（与 user-service 共用密钥）。
 */
@Component
public class JwtTokenParser {

    private final SecretKey secretKey;

    public JwtTokenParser(@Value("${supportflow.auth.jwt-secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public LoginUser requireLoginUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(authorization.substring(7))
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
