package io.supportflow.tenant.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 注册审核策略客户端（tenant-service 侧）：把平台「平台设置 → 注册与审核」的三个开关接到开通链路上。
 *
 * <p>策略存在 {@code ops_db.register_config}，这里缓存 30 秒后重新拉一次——运营改完策略应该很快生效，
 * 但不必企业每提交一次资料就问一次 ops。</p>
 *
 * <p>三件事用它：</p>
 * <ol>
 *   <li>{@link #trialDays()}：审核通过时按平台配置的天数开试用；</li>
 *   <li>{@link #needReview()}：平台关掉"需运营审核"后，提交即开通，不进人工队列；</li>
 *   <li>{@link #domainAutoApproved(String)}：企业邮箱域名命中白名单，同样提交即开通。</li>
 * </ol>
 *
 * <p><b>拿不到策略时按保守值处理</b>（需审核、试用 14 天）：宁可让人进人工队列（人工能拦、能补审），
 * 也不要因为运营服务抖一下就把没核验过的企业直接放进来——跟 user-service 那份"宁可放进来走审核"
 * 的默认值方向不同，因为那边守的是"注册入口"，这边守的是"开通闸门"。</p>
 */
@Component
public class RegistrationPolicyClient {

    private static final Logger log = LoggerFactory.getLogger(RegistrationPolicyClient.class);
    private static final long CACHE_MILLIS = 30_000L;

    private final RestClient restClient;

    private volatile long cachedAt = 0L;
    private volatile boolean needReview = true;
    private volatile int trialDays = 14;
    /** 白名单域名（小写，逗号分隔；为空表示没配） */
    private volatile String autoApproveDomains = "";

    public RegistrationPolicyClient(@Value("${supportflow.ops.internal-base-url:http://127.0.0.1:9095}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(1));
                    setReadTimeout(Duration.ofSeconds(2));
                }})
                .build();
    }

    /** 试用天数（平台策略；读不到返回 14） */
    public int trialDays() {
        refresh();
        return trialDays;
    }

    /** 新注册企业是否需要运营人工审核（默认需要） */
    public boolean needReview() {
        refresh();
        return needReview;
    }

    /** 企业联系邮箱的域名是否命中白名单（命中 → 提交即放行，不进人工队列） */
    public boolean domainAutoApproved(String email) {
        refresh();
        String domains = autoApproveDomains;
        if (domains.isBlank() || email == null || !email.contains("@")) {
            return false;
        }
        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase();
        if (domain.isEmpty()) {
            return false;
        }
        return domainList(domains).contains(domain);
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
                needReview = !Boolean.FALSE.equals(map.get("needReview"));
                trialDays = intOf(map.get("trialDays"), trialDays);
                autoApproveDomains = domainsOf(map.get("autoApproveDomains"));
            }
        } catch (Exception e) {
            log.debug("取注册策略失败，沿用上一次（或保守默认值：需审核 / 试用 14 天）：{}", e.getMessage());
        }
    }

    /** 域名列表归一化：去空、去重、小写（与 ops 保存时同一套口径） */
    private static String domainsOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String domain : domainList(list)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(domain);
        }
        return sb.toString();
    }

    private static List<String> domainList(Object value) {
        List<String> result = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            // 逗号分隔的字符串也认（防御性：ops 换了返回形态也别把白名单读成空）
            if (value != null) {
                for (String item : String.valueOf(value).split(",")) {
                    addDomain(result, item);
                }
            }
            return result;
        }
        for (Object item : list) {
            for (String part : String.valueOf(item == null ? "" : item).split(",")) {
                addDomain(result, part);
            }
        }
        return result;
    }

    private static void addDomain(List<String> target, String raw) {
        if (raw == null) {
            return;
        }
        String domain = raw.trim().toLowerCase();
        if (!domain.isEmpty() && !target.contains(domain)) {
            target.add(domain);
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
