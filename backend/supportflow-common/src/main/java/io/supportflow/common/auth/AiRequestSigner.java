package io.supportflow.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/** Credentials for requests whose audience is SupportFlow AI. Never sign with the login JWT key. */
public final class AiRequestSigner {
    public static final String HEADER = "X-SupportFlow-AI-Auth";

    private AiRequestSigner() {
    }

    public static String credential(String secret, String caller, String method, String rawPath, String tenant) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("The AI service credential is not configured");
        }
        long expires = Instant.now().plusSeconds(30).getEpochSecond();
        String message = String.join("\n", "supportflow-ai:v1", caller, Long.toString(expires),
                method, rawPath, tenant == null ? "" : tenant);
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return caller + ":" + expires + ":" + HexFormat.of().formatHex(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign AI service request", e);
        }
    }

    public static HttpRequest sign(HttpRequest request, String secret, String caller, String tenant) {
        return HttpRequest.newBuilder(request, (name, value) ->
                        !name.equalsIgnoreCase(HEADER) && !name.equalsIgnoreCase("X-Tenant-Code"))
                .header("X-Tenant-Code", tenant == null ? "" : tenant)
                .header(HEADER, credential(secret, caller, request.method(), request.uri().getRawPath(), tenant))
                .build();
    }
}
