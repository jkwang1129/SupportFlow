package io.supportflow.customer.security;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.LoginUserContext;
import io.supportflow.common.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20) // after verified LoginUserFilter, before transactions
public class CustomerPrivilegeFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    public CustomerPrivilegeFilter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String licenseScope(LoginUser user) {
        if (user == null || user.userType() != 2 || user.userId() <= 0) {
            throw new io.supportflow.common.exception.BizException(40301, "仅企业申请人可上传营业执照");
        }
        return "L" + Long.toString(user.userId(), 36);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean license = path.startsWith("/api/customer/files/enterprise/");
        boolean platform = path.equals("/api/platform/tickets") || path.startsWith("/api/platform/tickets/")
                || path.equals("/api/customer/platform/ai-quality");
        if (!license && !platform) { chain.doFilter(request, response); return; }
        LoginUser user = LoginUserContext.get();
        if (user == null || user.userId() <= 0 || (platform && user.userType() != 1)
                || (license && user.userType() != 1 && user.userType() != 2)) {
            response.setStatus(user == null ? 401 : 403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":40301,\"message\":\"无权访问\"}");
            return;
        }
        try {
        if (license && user.userType() == 2) {
            TenantContext.bind(licenseScope(user), () -> { chain.doFilter(request, response); return null; });
            return;
        }
        PlatformAccess.call(() -> {
            // A persistent audit receipt is mandatory before the privileged operation.
            audit(user, request, "START", response.getStatus());
            try { chain.doFilter(request, response); }
            catch (Exception failure) {
                audit(user, request, "FAILED", 500);
                throw failure;
            }
            audit(user, request, "END", response.getStatus());
            return null;
        });
        } catch (IOException | ServletException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ServletException(failure);
        }
    }
    private void audit(LoginUser user, HttpServletRequest request, String phase, int status) {
        jdbc.update("INSERT INTO public.platform_access_audit(actor_id, operation, phase, http_status) VALUES (?, ?, ?, ?)",
                user.userId(), request.getMethod() + " " + request.getServletPath(), phase, status);
    }
}
