package io.supportflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 租户级限流与隔离配置（网关是执行点：验身份、认租户、限流）。 */
@ConfigurationProperties(prefix = "supportflow.gateway.tenant-guard")
public class TenantGuardProperties {

    /** 是否启用租户级限流（关掉只剩"认证 + 租户头校验"） */
    private boolean rateLimitEnabled = true;

    /**
     * 拉不到租户配额时的兜底 QPS（tenant-service 挂了也不能让整个平台失去保护）。
     * 取"保守但不至于卡死业务"的值。
     */
    private int defaultQps = 20;

    /** 配额缓存时间（秒）：避免每个请求都去问 tenant-service */
    private int quotaCacheSeconds = 30;

    /** 用量上报间隔（秒） */
    private int usageReportSeconds = 5;

    /**
     * 免限流前缀：内部接口（服务间调用）、认证接口（登录/验证码/注册，
     * 否则被限流就登不进来了）、平台隔离管理接口（出事时平台得能进去改）。
     */
    private List<String> skipPrefixes = new ArrayList<>(List.of(
            "/api/user/auth/",
            "/api/tenant/internal/",
            "/api/customer/internal/",
            // 计费内部接口（计量上报）：由服务侧定时调用，不该被租户限流影响
            "/api/billing/internal/",
            "/api/realtime/internal/",
            // 运营内部接口（审计日志写入）：服务间调用，业务操作不能因为写日志被限流挡住
            "/api/ops/internal/",
            "/api/tenant/isolation/"
    ));

    /** tenant-service 内部接口地址 */
    private String tenantServiceUrl = "http://127.0.0.1:9092";

    /** JWT 签名密钥（与 user-service 一致，HS256） */
    private String jwtSecret = "supportflow-customer-service-jwt-secret-please-change-in-prod-0123456789";

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public int getDefaultQps() {
        return defaultQps;
    }

    public void setDefaultQps(int defaultQps) {
        this.defaultQps = defaultQps;
    }

    public int getQuotaCacheSeconds() {
        return quotaCacheSeconds;
    }

    public void setQuotaCacheSeconds(int quotaCacheSeconds) {
        this.quotaCacheSeconds = quotaCacheSeconds;
    }

    public int getUsageReportSeconds() {
        return usageReportSeconds;
    }

    public void setUsageReportSeconds(int usageReportSeconds) {
        this.usageReportSeconds = usageReportSeconds;
    }

    public List<String> getSkipPrefixes() {
        return skipPrefixes;
    }

    public void setSkipPrefixes(List<String> skipPrefixes) {
        this.skipPrefixes = skipPrefixes;
    }

    public String getTenantServiceUrl() {
        return tenantServiceUrl;
    }

    public void setTenantServiceUrl(String tenantServiceUrl) {
        this.tenantServiceUrl = tenantServiceUrl;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
