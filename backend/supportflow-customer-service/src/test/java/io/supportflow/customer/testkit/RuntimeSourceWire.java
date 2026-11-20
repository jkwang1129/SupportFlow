package io.supportflow.customer.testkit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Independent synthetic protocol fixture, also pinned by the Python tests. */
public final class RuntimeSourceWire {
    public static final String KEY = "source-test-pair-000000000000000000000000000";
    public static final String PATH = "/api/customer/internal/runtime/events/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/snapshot";
    public static String credential(String method, String target, String tenant, byte[] body, long expiry) {
        try {
            URI uri = URI.create(target);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String input = String.join("\n", "supportflow-customer-runtime:v1", "ai", Long.toString(expiry), method,
                    uri.getRawPath(), uri.getRawQuery() == null ? "" : uri.getRawQuery(), tenant, hash);
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "runtime:ai:" + expiry + ":" + hash + ":" + HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
