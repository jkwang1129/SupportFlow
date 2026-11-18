package io.supportflow.billing.security;

import io.supportflow.common.auth.AuthState;
import io.supportflow.common.auth.LoginUserResolver;
import io.supportflow.common.exception.BizException;
import org.springframework.stereotype.Component;

/**
 * billing-service 的登录态解析器：把 JWT 解析从每个控制器挪到请求入口做一次。
 */
@Component
public class JwtLoginUserResolver implements LoginUserResolver {

    private final JwtTokenParser jwtTokenParser;

    public JwtLoginUserResolver(JwtTokenParser jwtTokenParser) {
        this.jwtTokenParser = jwtTokenParser;
    }

    @Override
    public AuthState resolve(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return AuthState.anonymous();
        }
        try {
            return AuthState.ok(jwtTokenParser.requireLoginUser(authorization));
        } catch (BizException e) {
            // 不在这里 401：有些接口允许匿名访问，拦不拦由控制器调 require() 决定
            return AuthState.failed(e);
        }
    }
}
