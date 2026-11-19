package io.supportflow.common.readiness;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Probes remain reachable; business handlers must not run against an unusable schema. */
public final class SchemaReadinessFilter extends OncePerRequestFilter {
    private final SchemaReadiness readiness;
    public SchemaReadinessFilter(SchemaReadiness readiness) { this.readiness = readiness; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/") && !readiness.ready()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "5");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"message\":\"Service is not ready\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
