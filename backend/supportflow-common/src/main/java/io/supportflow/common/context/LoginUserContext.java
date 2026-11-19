package io.supportflow.common.context;

import io.supportflow.common.api.ResultCode;
import io.supportflow.common.auth.AuthState;
import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.exception.BizException;

import java.lang.ScopedValue.CallableOp;

/**
 * 登录态上下文（{@link ScopedValue}，JDK 25 正式 API）。
 *
 * <p>为什么不用 ThreadLocal：ThreadLocal 要靠"set 完记得 remove"的纪律，
 * 线程池复用时漏一次就会把上一个请求的登录态带给下一个请求；而且每个线程一张副本，
 * 虚拟线程一多就是内存灾难。ScopedValue 的绑定只在
 * {@code ScopedValue.where(...).call(...)} 这段代码范围内有效，出了范围自动失效——
 * 没有 set/remove 这一对，也就没有"忘记清理"这个 bug 类别。</p>
 *
 * <p>解析只做一次：入口过滤器（{@code LoginUserFilter}）把 Authorization 头解析成
 * {@link AuthState} 绑进来，控制器只读不解析。解析失败不直接 401，而是把原因装进
 * AuthState——有些接口允许匿名访问，要不要拦由控制器说了算。</p>
 *
 * <p>绑定时顺带把租户也绑进 {@link TenantContext}：库上有 RLS 兜底（策略读
 * {@code app.tenant_code}），登录态里就有权威租户，没必要让业务代码再手工设一次。
 * {@code PLATFORM} 不属于普通业务租户；跨租户操作必须走独立的授权与数据库身份。</p>
 */
public final class LoginUserContext {

        private static final ScopedValue<AuthState> CURRENT = ScopedValue.newInstance();

    private LoginUserContext() {
    }

    /** 当前请求有没有绑定过鉴权结果（匿名接口也会绑，只是 user 为空）。 */
    public static boolean isBound() {
        return CURRENT.isBound();
    }

    /**
     * 这次请求带没带令牌（哪怕带的是错的）。
     *
     * <p>有接口是"带了令牌就走坐席身份、没带就按访客令牌校验"（例如访客页的满意度评价，
     * 坐席也能代录），这种地方判断依据是"请求头在不在"，不能只看解析结果。</p>
     */
    public static boolean tokenPresent() {
        if (!CURRENT.isBound()) {
            return false;
        }
        AuthState state = CURRENT.get();
        return state != null && state.tokenPresent();
    }

    /** 当前登录用户；匿名请求返回 null。 */
    public static LoginUser get() {
        if (!CURRENT.isBound()) {
            return null;
        }
        AuthState state = CURRENT.get();
        return state == null ? null : state.user();
    }

    /**
     * 要求已登录：拿不到就抛未认证异常。
     *
     * <p>入口解析过但失败了（令牌过期 / 签名不对）时，抛的是入口那份原始异常，
     * 保证提示文案与改造前一致。</p>
     */
    public static LoginUser require() {
        LoginUser user = get();
        if (user != null) {
            return user;
        }
        if (CURRENT.isBound()) {
            AuthState state = CURRENT.get();
            if (state != null && state.failure() != null) {
                throw state.failure();
            }
        }
        throw new BizException(ResultCode.UNAUTHORIZED);
    }

    /** 是不是平台账号（userType=1）；未登录返回 false。 */
    public static boolean isPlatform() {
        LoginUser user = get();
        return user != null && user.userType() == 1;
    }

    /**
     * 在某段代码里绑定鉴权结果。只给入口过滤器用，业务代码不要调。
     *
     * <p>绑定只对这个 action 的调用栈生效，而且必须是同一线程。</p>
     */
    public static <R, X extends Throwable> R bind(AuthState state, CallableOp<? extends R, X> action)
            throws X {
        ScopedValue.Carrier carrier = ScopedValue.where(CURRENT, state);
        LoginUser user = state.user();
        String tenant = user == null ? null : user.tenantCode();
        if (tenant != null && !tenant.isBlank()) {
            // 同一次绑定里把租户也带上：RLS 借连接时会读它
            carrier = carrier.where(TenantContext.CURRENT, tenant);
        }
        return carrier.call(action);
    }
}
