package io.supportflow.user.security;

import io.supportflow.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 忘记密码验证码存储（单机演示用，生产建议替换 Redis + TTL + 限流）。
 *
 * <p>安全策略：6 位数字、10 分钟有效、60 秒内不可重复发送、最多尝试 5 次、验证成功即失效。</p>
 */
@Component
public class PasswordResetStore {

    private static final long EXPIRE_MILLIS = 10 * 60 * 1000L;
    private static final long RESEND_INTERVAL_MILLIS = 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Entry> store = new ConcurrentHashMap<>();

    /**
     * 生成并保存验证码；60 秒内重复请求会被拒绝。
     */
    public String issue(long userId) {
        long now = System.currentTimeMillis();
        Entry existing = store.get(userId);
        if (existing != null && now - existing.sentAt() < RESEND_INTERVAL_MILLIS) {
            long waitSeconds = (RESEND_INTERVAL_MILLIS - (now - existing.sentAt())) / 1000 + 1;
            throw new BizException(40002, "验证码已发送，请 " + waitSeconds + " 秒后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        store.put(userId, new Entry(code, now, now + EXPIRE_MILLIS, 0));
        return code;
    }

    /**
     * 校验验证码，成功后立即失效。
     */
    public boolean verify(long userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        Entry entry = store.get(userId);
        if (entry == null || entry.expireAt() < System.currentTimeMillis()) {
            store.remove(userId);
            return false;
        }
        if (entry.attempts() >= MAX_ATTEMPTS) {
            store.remove(userId);
            throw new BizException(40002, "验证码尝试次数过多，请重新获取");
        }
        if (!entry.code().equals(code.trim())) {
            store.put(userId, new Entry(entry.code(), entry.sentAt(), entry.expireAt(), entry.attempts() + 1));
            return false;
        }
        store.remove(userId);
        return true;
    }

    private record Entry(String code, long sentAt, long expireAt, int attempts) {
    }
}
