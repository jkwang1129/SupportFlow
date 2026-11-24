package io.supportflow.common.auth;

import io.supportflow.common.context.TenantContext;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Signed customer service traffic. The caller role binds the direction and purpose. */
public final class CustomerInternalCredential {
    public static final String HEADER = "X-SupportFlow-Customer-Auth";
    private static final String AUDIENCE = "supportflow-customer:v1";
    private static final long TTL_SECONDS = 30;
    private static final Pattern FORMAT = Pattern.compile("([0-9]{1,12}):([a-f0-9]{64})");

    public enum Caller { REALTIME, OPS, TENANT, CUSTOMER_NOTICE }

    private CustomerInternalCredential() {
    }

    public static ClientHttpRequestInterceptor signer(String secret, Caller caller) {
        return (request, body, execution) -> {
            var uri = request.getURI();
            String tenant = request.getHeaders().getFirst(TenantContext.HEADER_TENANT_CODE);
            request.getHeaders().set(HEADER, sign(secret, caller, request.getMethod().name(),
                    uri.getRawPath(), uri.getRawQuery(), tenant, Clock.systemUTC()));
            return execution.execute(request, body);
        };
    }

    public static String sign(String secret, Caller caller, String method, String rawPath, String rawQuery,
                              String tenant, Clock clock) {
        if (!usable(secret)) {
            throw new IllegalStateException("Customer internal credential is not configured");
        }
        String expires = Long.toString(clock.instant().getEpochSecond() + TTL_SECONDS);
        return expires + ":" + HexFormat.of().formatHex(digest(secret, caller, expires, method, rawPath, rawQuery, tenant));
    }

    public static boolean verify(String credential, String secret, Caller caller, String method, String rawPath,
                                 String rawQuery, String tenant, Clock clock) {
        if (!usable(secret) || credential == null) {
            return false;
        }
        var match = FORMAT.matcher(credential);
        if (!match.matches()) {
            return false;
        }
        String expires = match.group(1);
        long seconds = Long.parseLong(expires);
        long now = clock.instant().getEpochSecond();
        return seconds > now && seconds <= now + TTL_SECONDS + 5
                && MessageDigest.isEqual(HexFormat.of().parseHex(match.group(2)),
                digest(secret, caller, expires, method, rawPath, rawQuery, tenant));
    }

    public static boolean usable(String secret) {
        return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private static byte[] digest(String secret, Caller caller, String expires, String method, String rawPath,
                                 String rawQuery, String tenant) {
        String message = String.join("\n", AUDIENCE, caller.name(), expires, method, rawPath,
                rawQuery == null ? "" : rawQuery, tenant == null ? "" : tenant);
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign customer internal request", e);
        }
    }
}
