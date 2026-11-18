package io.supportflow.user.security;

import io.supportflow.common.exception.BizException;
import io.supportflow.user.dto.Challenge;
import io.supportflow.user.dto.Identity;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录二次验证（双因素）的验证码存储。
 *
 * <p>形态和 {@link PasswordResetStore} 一致（6 位数字、限时、一次性、错误次数上限），
 * 但**键是 challengeId 而不是 userId**：挑战号是"密码已经验证通过"这件事的凭据，
 * 第二步只认挑战号，攻击者拿一个 userId 是换不到 token 的。</p>
 *
 * <p>安全参数：5 分钟有效、60 秒内不能重复发、最多错 5 次、验证成功立即失效。
 * 单机内存实现（与验证码、登录锁定同一套做法），生产多实例应换 Redis。</p>
 */
@Component
public class TwoFactorStore {

    private static final long EXPIRE_MILLIS = 5 * 60 * 1000L;
    private static final long RESEND_INTERVAL_MILLIS = 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    /** 前端展示与倒计时用（秒） */
    public static final int EXPIRE_SECONDS = (int) (EXPIRE_MILLIS / 1000);
    public static final int RESEND_INTERVAL_SECONDS = (int) (RESEND_INTERVAL_MILLIS / 1000);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /** 密码校验通过后开一个挑战，返回挑战号与验证码（验证码由调用方发到账号邮箱） */
    public Challenge issue(long userId, String account, String tenantCode) {
        evictExpired();
        String challengeId = UUID.randomUUID().toString().replace("-", "");
        String code = String.format("%06d", random.nextInt(1_000_000));
        long now = System.currentTimeMillis();
        store.put(challengeId, new Entry(userId, account, tenantCode, code, now, now + EXPIRE_MILLIS, 0));
        return new Challenge(challengeId, code);
    }

    /**
     * 重发验证码：挑战号不变（前端不用换页面），只换码并重置有效期；
     * 60 秒内重复点会被拒（防止有人拿邮箱当短信轰炸）。
     */
    public String resend(String challengeId) {
        Entry entry = alive(challengeId);
        long now = System.currentTimeMillis();
        if (now - entry.sentAt() < RESEND_INTERVAL_MILLIS) {
            long waitSeconds = (RESEND_INTERVAL_MILLIS - (now - entry.sentAt())) / 1000 + 1;
            throw new BizException(40002, "验证码已发送，请 " + waitSeconds + " 秒后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        store.put(challengeId, new Entry(entry.userId(), entry.account(), entry.tenantCode(),
                code, now, now + EXPIRE_MILLIS, 0));
        return code;
    }

    /**
     * 这次挑战对应的身份（不消费）。
     *
     * <p>先取身份再校验码，是为了"码错了也要知道是谁在试"——不然失败次数没法记到账号上，
     * 登录日志也只能写个空账号。</p>
     */
    public Identity peek(String challengeId) {
        Entry entry = alive(challengeId);
        return new Identity(entry.userId(), entry.account(), entry.tenantCode());
    }

    /**
     * 校验验证码：对了立即失效（一次性）并返回 true；错了累计次数并返回 false。
     *
     * @throws BizException 挑战不存在 / 已过期 / 错误次数过多——这三种都必须重新走第一步登录
     */
    public boolean verify(String challengeId, String code) {
        Entry entry = alive(challengeId);
        if (entry.attempts() >= MAX_ATTEMPTS) {
            store.remove(challengeId);
            throw new BizException(40002, "验证码尝试次数过多，请重新登录");
        }
        if (code == null || !entry.code().equals(code.trim())) {
            store.put(challengeId, new Entry(entry.userId(), entry.account(), entry.tenantCode(),
                    entry.code(), entry.sentAt(), entry.expireAt(), entry.attempts() + 1));
            return false;
        }
        store.remove(challengeId);
        return true;
    }

    /** 挑战还在不在（重发之前先探一下，过期了就直接告诉前端"重新登录"） */
    private Entry alive(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            throw new BizException(40002, "验证码已失效，请重新登录");
        }
        Entry entry = store.get(challengeId);
        if (entry == null || entry.expireAt() < System.currentTimeMillis()) {
            store.remove(challengeId);
            throw new BizException(40002, "验证码已失效，请重新登录");
        }
        return entry;
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(item -> item.getValue().expireAt() < now);
    }

    private record Entry(long userId, String account, String tenantCode, String code,
                         long sentAt, long expireAt, int attempts) {
    }
}
