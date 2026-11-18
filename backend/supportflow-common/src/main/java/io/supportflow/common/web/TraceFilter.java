package io.supportflow.common.web;

import io.supportflow.common.context.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 调用链入口（Servlet 服务通用）。
 *
 * <p>三件事：① 取上游（网关 / 前端）带来的 {@code X-Request-Id}，没有就生成一个；
 * ② 绑进 {@link TraceContext}（ScopedValue）并写日志 MDC（logback pattern 里
 * {@code %X{traceId}} 直接可见）；③ 回写响应头，让前端能把这个号显示在错误提示里。</p>
 *
 * <p>绑定必须包住整条 {@code chain.doFilter}，出了这段作用域自动失效——不再需要
 * "finally 里 clear"这一步。只有 MDC 还得手动清：那是日志框架自己的 ThreadLocal，
 * ScopedValue 管不到它。</p>
 *
 * <p>{@code @ConditionalOnWebApplication(type = SERVLET)} 是必须的：网关是 WebFlux
 * （类路径上没有 Servlet API），这个 Bean 被扫到就会在启动时炸。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceContext.HEADER_REQUEST_ID);
        String traceId = incoming == null || incoming.isBlank()
                ? TraceContext.newTraceId() : incoming.trim();
        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(TraceContext.HEADER_REQUEST_ID, traceId);
        try {
            TraceContext.bind(traceId, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        } finally {
            // MDC 是日志框架的 ThreadLocal：线程复用，不清理会把上一个请求的号带下去
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
