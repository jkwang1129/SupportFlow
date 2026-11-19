package io.supportflow.gateway.filter;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.auth.AiRequestSigner;
import io.supportflow.gateway.security.JwtTenantParser;
import io.supportflow.gateway.support.RequestIps;
import io.supportflow.gateway.support.TenantQuotaCache;
import io.supportflow.gateway.support.TenantUsageReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 隔离第一道门的"认租户"：网关负责把权威租户钉死，别让下游被请求头骗。
 *
 * <p>三件事：
 * 1. 有令牌就从令牌里验签取出 tenant_code（**权威值**），覆盖请求头里的 X-Tenant-Code；
 * 2. 请求头里带的租户和令牌不一致 → 直接 403，并记一条安全事件（有人想越权）；
 * 3. 冻结的租户（tenant.status=2）→ 403，不进业务服务。</p>
 *
 * <p>为什么必须在网关做：下游服务虽然都以令牌为准，但"前端可以随便改 X-Tenant-Code"
 * 这件事本身就该在门口被拦下来并留痕，而不是让每个服务各自防御。</p>
 */
@Component
public class TenantGuardFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantGuardFilter.class);
    private static final PathPattern CUSTOMER_INTERNAL =
            PathPatternParser.defaultInstance.parse("/api/customer/internal/**");

    private final JwtTenantParser jwtTenantParser;
    private final TenantQuotaCache quotaCache;
    private final TenantUsageReporter reporter;
    private final String aiSecret;

    public TenantGuardFilter(JwtTenantParser jwtTenantParser,
                             TenantQuotaCache quotaCache,
                             TenantUsageReporter reporter,
                             @Value("${SUPPORTFLOW_AI_GATEWAY_SECRET:}") String aiSecret) {
        this.jwtTenantParser = jwtTenantParser;
        this.quotaCache = quotaCache;
        this.reporter = reporter;
        this.aiSecret = aiSecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // Realtime, tenant and ops call these controllers directly. Do not allow a public
        // request to cause a trusted customer-service call to AI on an unverified tenant's behalf.
        if (CUSTOMER_INTERNAL.matches(request.getPath().pathWithinApplication())) {
            return reject(exchange, HttpStatus.NOT_FOUND, 40400, "此接口未对外开放");
        }
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        boolean ai = route != null && AiGatewayAccess.ROUTE_ID.equals(route.getId());
        if (ai) {
            String path = request.getURI().getRawPath();
            if (AiGatewayAccess.isPublic(request.getMethod(), path)) {
                ServerHttpRequest probe = request.mutate().headers(headers -> {
                    headers.remove(AiRequestSigner.HEADER);
                    headers.remove(TenantContext.HEADER_TENANT_CODE);
                    headers.remove("Authorization");
                }).build();
                return chain.filter(exchange.mutate().request(probe).build());
            }
            if (!AiGatewayAccess.isConsole(request.getMethod(), path)) {
                return reject(exchange, HttpStatus.NOT_FOUND, 40400, "此 AI 接口未对外开放");
            }
        }
        String header = request.getHeaders().getFirst(TenantContext.HEADER_TENANT_CODE);
        String authorization = request.getHeaders().getFirst("Authorization");
        JwtTenantParser.ParsedToken token = jwtTenantParser.parse(bearer(authorization));

        if (ai && (token == null || token.expireAt() <= Instant.now().getEpochSecond()
                || token.userId() == null || token.userId().isBlank())) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, 40100, "请登录后使用此 AI 接口");
        }
        if (token == null) {
            // 未认证的请求（访客侧接口）走"请求头里的租户"，由各服务自己校验 appKey / 访客令牌
            return chain.filter(exchange);
        }
        String tokenTenant = token.tenantCode();
        if (ai && (token.userType() != 2 || "PLATFORM".equals(tokenTenant))) {
            return reject(exchange, HttpStatus.FORBIDDEN, 40300, "此 AI 接口仅限企业账号");
        }
        if (tokenTenant == null || tokenTenant.isBlank()) {
            return reject(exchange, HttpStatus.FORBIDDEN, 40302, "令牌里没有租户信息，请重新登录");
        }
        if (header != null && !header.isBlank() && !header.equals(tokenTenant)) {
            reporter.reportEvent(tokenTenant, 1,
                    "请求头租户与令牌不一致：header=" + header + " token=" + tokenTenant,
                    request.getURI().getPath(), request.getMethod().name(),
                    RequestIps.clientIp(exchange), tokenTenant);
            log.warn("租户头与令牌不一致，已拒绝 uri={} header={} token={}",
                    request.getURI().getPath(), header, tokenTenant);
            return reject(exchange, HttpStatus.FORBIDDEN, 40303,
                    "请求租户与登录身份不一致，已拒绝并记录");
        }
        TenantQuotaCache.Quota quota = quotaCache.get(tokenTenant);
        if (quota.frozen()) {
            return reject(exchange, HttpStatus.FORBIDDEN, 40304,
                    "该企业账号已冻结，请联系平台客服");
        }
        // 把权威租户写给下游（下游仍以令牌为准，这一层是"统一口径 + 便于排查"）
        String aiCredential;
        try {
            aiCredential = ai ? AiRequestSigner.credential(aiSecret, "gateway", request.getMethod().name(),
                    request.getURI().getRawPath(), tokenTenant) : null;
        } catch (IllegalStateException e) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, 50300, "AI 服务暂不可用");
        }
        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    headers.set(TenantContext.HEADER_TENANT_CODE, tokenTenant);
                    if (aiCredential != null) {
                        headers.set(AiRequestSigner.HEADER, aiCredential);
                        headers.remove("Authorization");
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private static String bearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
    }

    /** 统一的拒绝响应：业务码 + 人话，前端按 message 提示 */
    static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, int code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        // 比限流过滤器先执行：先把"你是谁"钉死，再谈"你能来多快"
        return -100;
    }
}
