package io.supportflow.customer.nativechannel;

import io.supportflow.common.exception.BizException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 原生渠道回调签名（App / 400 热线 / 邮件网关 统一用这一套）。
 *
 * <p>约定：请求头带 {@code X-SupportFlow-Timestamp}（毫秒）与 {@code X-SupportFlow-Signature}，
 * 签名 = HMAC-SHA256(secret, timestamp + "\n" + body) 的十六进制小写。
 * 带上时间戳并限制 5 分钟窗口，是为了防止回调被录下来重放。</p>
 */
public final class NativeSignature {

    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;

    private NativeSignature() {
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal((timestamp + "\n" + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new BizException(50001, "计算回调签名失败：" + e.getMessage());
        }
    }

    /** 校验签名与时间窗口；secret 没配时返回 true（允许不启用签名，方便本地联调） */
    public static boolean verify(String secret, String timestamp, String signature, String body, long nowMillis) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowMillis - sent) > WINDOW_MILLIS) {
            return false;
        }
        return sign(secret, timestamp, body).equalsIgnoreCase(signature.trim());
    }
}
