package io.supportflow.user.security;

import io.supportflow.common.auth.AuthState;
import io.supportflow.common.auth.LoginUserResolver;
import io.supportflow.common.exception.BizException;
import org.springframework.stereotype.Component;

/**
 * user-service 的登录态解析器：把 JWT 解析从每个控制器挪到请求入口做一次。
 *
 * <p>这里顺带吸收掉原先散在各控制器里的"剥 Bearer 前缀"（{@code resolveBearer} /
 * {@code bearer} / {@code parseBearer} 三个私有方法）。它们以前各自抛不同的错误码
 * （40100 / 40301），前端只会对 40100 触发自动登出——现在统一成 40100，
 * 令牌过期时能正确把用户踢回登录页。</p>
 */
@Component
public class JwtLoginUserResolver implements LoginUserResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtLoginUserResolver(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public AuthState resolve(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return AuthState.anonymous();
        }
        String value = authorization.trim();
        String token = value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                ? value.substring(BEARER_PREFIX.length()).trim() : value;
        try {
            return AuthState.ok(jwtService.parseToken(token));
        } catch (BizException e) {
            // 不在这里 401：有些接口允许匿名访问，拦不拦由控制器调 require() 决定
            return AuthState.failed(e);
        }
    }
}
