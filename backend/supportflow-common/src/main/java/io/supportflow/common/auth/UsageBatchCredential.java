package io.supportflow.common.auth;

import io.supportflow.common.billing.UsageBatch;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Dedicated customer -> billing audience; immutable batch contents are covered by the signature. */
public final class UsageBatchCredential {
    public static final String HEADER = "X-SupportFlow-Usage-Auth";
    public static final String PATH = "/api/billing/internal/usage/report";
    private static final Pattern FORMAT = Pattern.compile("([0-9]{1,12}):([a-f0-9]{64})");
    private UsageBatchCredential() {}

    public static String sign(String secret, UsageBatch batch, Clock clock) {
        if (!usable(secret)) throw new IllegalStateException("Billing customer credential is not configured");
        String expires = Long.toString(clock.instant().getEpochSecond() + 30);
        return expires + ":" + HexFormat.of().formatHex(digest(secret, batch, expires));
    }

    public static boolean verify(String credential, String secret, UsageBatch batch, Clock clock) {
        if (!usable(secret) || credential == null || batch == null) return false;
        var match = FORMAT.matcher(credential);
        if (!match.matches()) return false;
        long expires = Long.parseLong(match.group(1)), now = clock.instant().getEpochSecond();
        return expires > now && expires <= now + 35 && MessageDigest.isEqual(
                HexFormat.of().parseHex(match.group(2)), digest(secret, batch, match.group(1)));
    }

    private static boolean usable(String secret) {
        return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private static byte[] digest(String secret, UsageBatch batch, String expires) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(String.join("\n", "supportflow-billing:customer-usage:v1", "POST", PATH,
                    expires, batch.tenantCode(), batch.fingerprint()).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign usage batch", e);
        }
    }
}
