package io.supportflow.customer.security;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.auth.CustomerInternalCredential.Caller;
import io.supportflow.common.context.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Configuration
public class CustomerInternalAccess implements WebMvcConfigurer {
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,15}");
    private final Map<Caller, String> keys;

    public CustomerInternalAccess(@Value("${SUPPORTFLOW_CUSTOMER_REALTIME_SECRET:}") String realtime,
                                  @Value("${SUPPORTFLOW_CUSTOMER_OPS_SECRET:}") String ops,
                                  @Value("${SUPPORTFLOW_CUSTOMER_TENANT_SECRET:}") String tenant) {
        keys = Map.of(Caller.REALTIME, realtime, Caller.OPS, ops, Caller.TENANT, tenant);
        var configured = keys.values().stream().filter(CustomerInternalCredential::usable).toList();
        if (new HashSet<>(configured).size() != configured.size()) {
            throw new IllegalArgumentException("Customer service credentials must be distinct");
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor());
    }

    HandlerInterceptor interceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!(handler instanceof HandlerMethod method)) {
                    return true;
                }
                if (method.hasMethodAnnotation(RuntimeCustomerEndpoint.class)
                        || method.getBeanType().isAnnotationPresent(RuntimeCustomerEndpoint.class)) {
                    if (request.getAttribute(RuntimeCustomerCall.ATTRIBUTE) instanceof RuntimeCustomerCall) return true;
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                var guard = method.getMethodAnnotation(InternalCustomerEndpoint.class);
                if (guard == null) {
                    guard = method.getBeanType().getAnnotation(InternalCustomerEndpoint.class);
                }
                if (guard == null) {
                    Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    if (mapping != null && (mapping.toString().equals("/api/customer/internal")
                            || mapping.toString().startsWith("/api/customer/internal/"))) {
                        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        return false;
                    }
                    return true;
                }
                List<String> signatures = java.util.Collections.list(request.getHeaders(CustomerInternalCredential.HEADER));
                List<String> tenants = java.util.Collections.list(request.getHeaders(TenantContext.HEADER_TENANT_CODE));
                String tenant = tenants.isEmpty() ? null : tenants.getFirst();
                Caller caller = guard.value();
                if (signatures.size() != 1 || tenants.size() > 1 ||
                        !CustomerInternalCredential.verify(signatures.getFirst(), keys.get(caller), caller,
                                request.getMethod(), request.getRequestURI(), request.getQueryString(),
                                tenant, Clock.systemUTC())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                if (guard.tenantScoped() ? !isTenant(tenant) : tenant != null) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return false;
                }
                request.setAttribute(CustomerInternalCall.ATTRIBUTE, new CustomerInternalCall(tenant));
                return true;
            }
        };
    }

    private static boolean isTenant(String tenant) {
        return tenant != null && !"PLATFORM".equalsIgnoreCase(tenant) && TENANT.matcher(tenant).matches();
    }

    @RestControllerAdvice
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class MismatchHandler {
        @ExceptionHandler(CustomerInternalCall.ScopeMismatch.class)
        public ResponseEntity<ApiResponse<Void>> forbidden() {
            return ResponseEntity.status(403).body(ApiResponse.fail(40303, "请求租户与服务授权不一致"));
        }
    }
}
