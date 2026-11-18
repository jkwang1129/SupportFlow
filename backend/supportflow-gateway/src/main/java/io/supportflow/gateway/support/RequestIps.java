package io.supportflow.gateway.support;

import org.springframework.web.server.ServerWebExchange;

/**
 * 网关（WebFlux）侧的客户端 IP 取值：与 Servlet 侧的
 * {@code io.supportflow.common.web.RequestUtils#clientIp} 是同一套口径。
 *
 * <p>网关没有 Servlet 请求，拿不到那个工具类，但两个过滤器（租户守卫 / 限流）
 * 原来各抄了一份一模一样的实现——抄两份的代价是改了一处漏一处，
 * 所以搬到这个类里，两边共用。</p>
 */
public final class RequestIps {

    private RequestIps() {
    }

    /** 优先 {@code X-Forwarded-For} 第一段，取不到退回连接对端地址（可能为 null） */
    public static String clientIp(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? null : remote.getAddress().getHostAddress();
    }
}
