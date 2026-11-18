package io.supportflow.gateway.filter;

import io.supportflow.common.context.TraceContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 调用链号在网关这一段：补齐 + 透传 + 回写响应头。
 *
 * <p>顺序是 {@link Ordered#HIGHEST_PRECEDENCE}——比其他过滤器都早，
 * 这样后面任何一段（限流拒绝、租户越权、下游服务）产生的日志与响应都带得上同一个号。</p>
 *
 * <p>前端没带就生成：不能让"没有号"变成常态，否则排查时永远缺一段。</p>
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(TraceContext.HEADER_REQUEST_ID);
        String traceId = incoming == null || incoming.isBlank()
                ? TraceContext.newTraceId() : incoming.trim();
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(TraceContext.HEADER_REQUEST_ID, traceId)
                .build();
        // 回写响应头：前端拿它能把这个号显示给用户（"把这个号给运维"）
        exchange.getResponse().getHeaders().set(TraceContext.HEADER_REQUEST_ID, traceId);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
