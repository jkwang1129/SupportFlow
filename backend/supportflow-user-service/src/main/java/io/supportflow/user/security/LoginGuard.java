package io.supportflow.user.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防线：失败次数锁定 + 管理端 IP 白名单 + 双因素（登录二次验证）策略。
 *
 * <p>策略值来自平台「安全中心 → 安全策略」（ops-service 的 {@code /api/security/login-policy}），
 * 本地缓存 30 秒——安全策略改成 3 次锁定就该很快生效，但不能每次登录都去问一次。
 * ops 不可用时用<b>保守默认值</b>（5 次 / 15 分钟、白名单不生效）：宁可保护弱一点，
 * 也不能因为运维服务抖一下就把所有人锁在门外。</p>
 *
 * <p>锁定状态放在进程内存里（和验证码同一套做法，单机够用）；生产多实例时应换成 Redis。</p>
 */
@Component
public class LoginGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginGuard.class);

    /** 策略缓存时长：30 秒 */
    private static final long CACHE_MILLIS = 30_000L;

    private final RestClient restClient;
    /** 账号 → [失败次数, 锁定到（epoch 秒，0 表示没锁）] */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();

    private volatile long cachedAt = 0L;
    private volatile int failThreshold = 5;
    private volatile int lockMinutes = 15;
    private volatile boolean whitelistEnabled = false;
    private volatile String whitelist = "127.0.0.1";
    /** 双因素（登录二次验证）开关与覆盖范围：1-仅平台账号与企业管理员、2-全部账号 */
    private volatile boolean twoFactorRequired = false;
    private volatile int twoFactorScope = 1;

    public LoginGuard(@Value("${supportflow.ops.internal-base-url:http://127.0.0.1:9095}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Duration.ofSeconds(1));
                    setReadTimeout(Duration.ofSeconds(2));
                }})
                .build();
    }

    /** 是否被锁定；被锁定时返回剩余分钟（0 表示没锁） */
    public int lockedMinutes(String account) {
        long[] state = failures.get(normalize(account));
        if (state == null) {
            return 0;
        }
        long now = Instant.now().getEpochSecond();
        if (state[1] > now) {
            return (int) Math.max(1, (state[1] - now + 59) / 60);
        }
        return 0;
    }

    /** 记一次失败；达到阈值就锁定 */
    public void recordFailure(String account) {
        refreshPolicy();
        String key = normalize(account);
        long[] state = failures.computeIfAbsent(key, k -> new long[]{0L, 0L});
        synchronized (state) {
            state[0]++;
            if (failThreshold > 0 && state[0] >= failThreshold) {
                state[1] = Instant.now().getEpochSecond() + (long) lockMinutes * 60;
                state[0] = 0;
                log.warn("账号连续失败达到阈值，已锁定 {} 分钟 account={} threshold={}",
                        lockMinutes, key, failThreshold);
            }
        }
    }

    /** 登录成功清空失败计数 */
    public void recordSuccess(String account) {
        failures.remove(normalize(account));
    }

    /** IP 是否允许登录（白名单没开就一律允许） */
    public boolean ipAllowed(String ip) {
        refreshPolicy();
        if (!whitelistEnabled) {
            return true;
        }
        String client = ip == null ? "" : ip.trim();
        for (String allowed : whitelist.split(",")) {
            if (!allowed.isBlank() && allowed.trim().equals(client)) {
                return true;
            }
        }
        log.warn("登录被 IP 白名单拒绝 ip={} whitelist={}", client, whitelist);
        return false;
    }

    /** 当前策略（页面/日志排查用） */
    public Map<String, Object> currentPolicy() {
        refreshPolicy();
        return Map.of("failThreshold", failThreshold, "lockMinutes", lockMinutes,
                "ipWhitelistEnabled", whitelistEnabled, "ipWhitelist", whitelist,
                "twoFactorRequired", twoFactorRequired, "twoFactorScope", twoFactorScope);
    }

    /** 登录是否需要双因素（安全中心「强制双因素认证」开关；覆盖范围由调用方按账号判定） */
    public boolean twoFactorRequired() {
        refreshPolicy();
        return twoFactorRequired;
    }

    /** 双因素覆盖范围：1-仅平台账号与企业管理员、2-全部账号（含坐席） */
    public int twoFactorScope() {
        refreshPolicy();
        return twoFactorScope;
    }

    @SuppressWarnings("unchecked")
    private void refreshPolicy() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS) {
            return;
        }
        cachedAt = now;
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/ops/internal/security/login-policy")
                    .retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map) {
                failThreshold = intOf(map.get("failThreshold"), failThreshold);
                lockMinutes = intOf(map.get("lockMinutes"), lockMinutes);
                whitelistEnabled = Boolean.TRUE.equals(map.get("ipWhitelistEnabled"));
                Object list = map.get("ipWhitelist");
                if (list != null && !String.valueOf(list).isBlank()) {
                    whitelist = String.valueOf(list);
                }
                twoFactorRequired = Boolean.TRUE.equals(map.get("twoFactorRequired"));
                int scope = intOf(map.get("twoFactorScope"), twoFactorScope);
                twoFactorScope = scope == 2 ? 2 : 1;
            }
        } catch (Exception e) {
            log.debug("取安全策略失败，沿用上一次（或默认值）：{}", e.getMessage());
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

    private static String normalize(String account) {
        return account == null ? "" : account.trim().toLowerCase();
    }
}
