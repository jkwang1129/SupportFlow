package io.supportflow.user.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册审核策略客户端：把平台设置里那五个开关真正接到注册链路上。
 *
 * <p>策略存在 ops_db（平台「平台设置 → 注册与审核」），这里缓存 30 秒后重新拉一次——
 * 运营关掉"开放注册"应该很快生效，但不必每来一个注册都问一次 ops。</p>
 *
 * <p><b>拿不到策略时的默认值很关键</b>：按"允许注册 + 需要运营审核 + 试用 14 天"处理。
 * 宁可让人注册进来走审核（人工能拦），也不要因为运营服务抖一下就把所有企业挡在门外——
 * 反过来说，想真正停掉注册，得保证 ops 活着，这也是策略页面上会写清的前提。</p>
 */
@Component
public class RegistrationPolicyClient {

    private static final Logger log = LoggerFactory.getLogger(RegistrationPolicyClient.class);
    private static final long CACHE_MILLIS = 30_000L;

    private final RestClient restClient;

    private volatile long cachedAt = 0L;
    private volatile boolean allowRegister = true;
    private volatile boolean needReview = true;
    private volatile int trialDays = 14;
    private volatile String autoApproveDomains = "";
    private volatile boolean requireDoubleVerify = false;

    public RegistrationPolicyClient(
            @Value("${supportflow.ops.internal-base-url:http://127.0.0.1:9095}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(1));
                    setReadTimeout(Duration.ofSeconds(2));
                }})
                .build();
    }

    /** 是否开放企业注册（关掉就该注册不了） */
    public boolean allowRegister() {
        refresh();
        return allowRegister;
    }

    /** 当前策略快照（注册成功后按它决定是否自动放行 / 试用多少天） */
    public Map<String, Object> snapshot() {
        refresh();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowRegister", allowRegister);
        result.put("needReview", needReview);
        result.put("trialDays", trialDays);
        result.put("autoApproveDomains", autoApproveDomains);
        result.put("requireDoubleVerify", requireDoubleVerify);
        return result;
    }

    /** 邮箱域名是否命中白名单（命中 → 新企业自动放行，不进人工审核） */
    public boolean domainAutoApproved(String email) {
        refresh();
        if (autoApproveDomains.isBlank() || email == null || !email.contains("@")) {
            return false;
        }
        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase();
        for (String allowed : autoApproveDomains.split(",")) {
            if (!allowed.isBlank() && allowed.trim().equalsIgnoreCase(domain)) {
                return true;
            }
        }
        return false;
    }

    /** 注册是否需要手机 / 邮箱双重验证（安全中心「注册需手机 / 邮箱双重验证」开关） */
    public boolean requireDoubleVerify() {
        refresh();
        return requireDoubleVerify;
    }

    @SuppressWarnings("unchecked")
    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS) {
            return;
        }
        cachedAt = now;
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/ops/internal/security/registration-policy")
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                allowRegister = !Boolean.FALSE.equals(map.get("allowRegister"));
                needReview = !Boolean.FALSE.equals(map.get("needReview"));
                trialDays = intOf(map.get("trialDays"), trialDays);
                requireDoubleVerify = Boolean.TRUE.equals(map.get("requireDoubleVerify"));
                Object domains = map.get("autoApproveDomains");
                if (domains instanceof List<?> list) {
                    StringBuilder sb = new StringBuilder();
                    for (Object item : list) {
                        if (item != null && !String.valueOf(item).isBlank()) {
                            if (sb.length() > 0) {
                                sb.append(',');
                            }
                            sb.append(String.valueOf(item).trim().toLowerCase());
                        }
                    }
                    autoApproveDomains = sb.toString();
                }
            }
        } catch (Exception e) {
            log.debug("取注册策略失败，沿用上一次（或保守默认值）：{}", e.getMessage());
        }
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
