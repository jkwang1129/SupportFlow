package io.supportflow.common.web;

import io.supportflow.common.auth.AuthState;
import io.supportflow.common.auth.LoginUserResolver;
import io.supportflow.common.context.LoginUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 登录态入口：统一把 Authorization 头解析成 {@link io.supportflow.common.auth.LoginUser}
 * 并绑进 {@link LoginUserContext}（ScopedValue），控制器只读它、不再声明请求头参数。
 *
 * <p>改造前是每个接口自己声明 {@code @RequestHeader String authorization} 再调一次解析器，
 * 两百多个方法各写一遍，漏了就等于这个接口不要登录。现在解析只有这一处。</p>
 *
 * <p>顺带把租户也绑进 {@link io.supportflow.common.context.TenantContext}：库上的 RLS 策略
 * 读 {@code app.tenant_code}，而登录态里就有权威租户。改造前这个上下文**从没被设过**，
 * RLS 一直是"上下文为空 → 全放行"，等于兜底没生效。</p>
 *
 * <p>绑定必须包住整条 {@code chain.doFilter}：ScopedValue 只在
 * {@code ScopedValue.where(...).call(...)} 的执行范围内有效，所以后续过滤器与控制器
 * 都要被包进来；出了这个范围自动失效，不需要也没法手动清理。</p>
 *
 * <p>已知边界：绑定是与线程绑定的。Servlet 同步链路里请求前后是同一个线程，没问题；
 * 异步任务（{@code @Async}）、定时任务、自己 new 的线程读不到——这是 ScopedValue 的设计，
 * 那些地方仍要显式传递。</p>
 *
 * <p>服务没提供 {@code LoginUserResolver} 时（网关是 WebFlux、或纯内部服务），
 * 过滤器直接放行，不改变任何行为。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LoginUserFilter extends OncePerRequestFilter {

    public static final String HEADER_AUTHORIZATION = "Authorization";

    private final ObjectProvider<LoginUserResolver> resolvers;

    public LoginUserFilter(ObjectProvider<LoginUserResolver> resolvers) {
        this.resolvers = resolvers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        LoginUserResolver resolver = resolvers.getIfAvailable();
        if (resolver == null) {
            chain.doFilter(request, response);
            return;
        }
        AuthState state = resolver.resolve(request.getHeader(HEADER_AUTHORIZATION));
        try {
            LoginUserContext.bind(state, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            // 业务/容器异常原样抛，别包一层把原有异常类型盖掉
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}
