package io.supportflow.customer.security;

import io.supportflow.common.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolve one active channel before opening its tenant-scoped transaction. */
@Component
public class ChannelTenantResolver {
    private final JdbcTemplate jdbc;

    public ChannelTenantResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String resolve(String appKey) {
        if (appKey == null || appKey.isBlank() || appKey.trim().length() > 64) {
            throw new BizException(40001, "缺少有效渠道密钥");
        }
        String tenant = jdbc.queryForObject("SELECT public.supportflow_channel_tenant(?)",
                String.class, appKey.trim());
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}")
                || "PLATFORM".equalsIgnoreCase(tenant)) {
            throw new BizException(40401, "渠道密钥无效、已过期或已停用");
        }
        return tenant;
    }

    public String wechatTenant(String appId) {
        if (appId == null || appId.isBlank() || appId.trim().length() > 64) {
            return null;
        }
        String tenant = jdbc.queryForObject("SELECT public.supportflow_wechat_tenant(?)",
                String.class, appId.trim());
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}")
                || "PLATFORM".equalsIgnoreCase(tenant)) {
            return null;
        }
        return tenant;
    }
}
