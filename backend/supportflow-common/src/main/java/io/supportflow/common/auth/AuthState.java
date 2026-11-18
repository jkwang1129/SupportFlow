package io.supportflow.common.auth;

import io.supportflow.common.exception.BizException;

/**
 * 一次请求的鉴权结果：成功给 {@code user}，失败把原因带上。
 *
 * <p>把失败原因带上是为了保住原来那句提示（"未认证或登录已过期"之类），
 * 否则统一在过滤器里 401 会把各服务不同的文案抹平。</p>
 */
public record AuthState(LoginUser user, BizException failure, boolean tokenPresent) {

    public static AuthState ok(LoginUser user) {
        return new AuthState(user, null, true);
    }

    public static AuthState failed(BizException failure) {
        return new AuthState(null, failure, true);
    }

    /** 请求根本没带 Authorization 头（允许匿名访问的接口走这条）。 */
    public static AuthState anonymous() {
        return new AuthState(null, null, false);
    }
}
