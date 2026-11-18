package io.supportflow.user.security;

import io.supportflow.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 注册邮箱验证码存储（安全中心「注册需手机 / 邮箱双重验证」的执行点）。
 *
 * <p>键是**邮箱**而不是 userId——注册时账号还没建出来，验证码要先发出去、注册时再校验。</p>
 *
 * <p>安全参数与登录二次验证保持一致：6 位数字、5 分钟有效、60 秒内不能重复发、
 * 最多错 5 次、验证成功即失效。发码接口还要求先过图形验证码（防止有人拿邮箱当轰炸目标）。</p>
 */
@Component
public class RegisterCodeStore {

    private static final long EXPIRE_MILLIS = 5 * 60 * 1000L;
    private static final long RESEND_INTERVAL_MILLIS = 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    /** 前端倒计时用（秒） */
    public static final int EXPIRE_SECONDS = (int) (EXPIRE_MILLIS / 1000);
    public static final int RESEND_INTERVAL_SECONDS = (int) (RESEND_INTERVAL_MILLIS / 1000);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /** 生成验证码；60 秒内重复请求会被拒。 */
    public String issue(String email) {
        evictExpired();
        String key = normalize(email);
        if (key.isEmpty()) {
            throw new BizException(40001, "邮箱不能为空");
        }
        long now = System.currentTimeMillis();
        Entry existing = store.get(key);
        if (existing != null && now - existing.sentAt() < RESEND_INTERVAL_MILLIS) {
            long waitSeconds = (RESEND_INTERVAL_MILLIS - (now - existing.sentAt())) / 1000 + 1;
            throw new BizException(40002, "验证码已发送，请 " + waitSeconds + " 秒后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        store.put(key, new Entry(code, now, now + EXPIRE_MILLIS, 0));
        return code;
    }

    /**
     * 校验验证码：对了立即失效（一次性）返回 true；错了累计次数返回 false。
     */
    public boolean verify(String email, String code) {
        String key = normalize(email);
        Entry entry = store.get(key);
        if (entry == null || entry.expireAt() < System.currentTimeMillis()) {
            store.remove(key);
            return false;
        }
        if (entry.attempts() >= MAX_ATTEMPTS) {
            store.remove(key);
            throw new BizException(40002, "验证码尝试次数过多，请重新获取");
        }
        if (code == null || !entry.code().equals(code.trim())) {
            store.put(key, new Entry(entry.code(), entry.sentAt(), entry.expireAt(),
                    entry.attempts() + 1));
            return false;
        }
        store.remove(key);
        return true;
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(item -> item.getValue().expireAt() < now);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private record Entry(String code, long sentAt, long expireAt, int attempts) {
    }
}
