package io.supportflow.customer.service.bot;

import io.supportflow.common.auth.AiRequestSigner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Body-bound runtime protocol. The bytes signed here are the bytes sent. */
public final class AgentRuntimeProtocol {
    private AgentRuntimeProtocol() { }

    public static HttpRequest request(URI uri, String method, String tenant, byte[] body, String secret) {
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null) {
            throw new IllegalArgumentException("Expected a configured runtime endpoint without query or credentials");
        }
        String path = uri.getRawPath();
        String base = "(?:/[A-Za-z0-9_-]+)*/ai/v1/runtime/runs";
        String run = base + "/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}";
        if (!("POST".equals(method) && (path.matches(base) || path.matches(base + "/ticket-approval-active")
                || path.matches(base + "/ticket-approval-latest-progress")
                || path.matches(run + "/cancel") || path.matches(run + "/authorize")
                || path.matches(run + "/review-ticket-approval") || path.matches(run + "/ticket-approval-preview"))
                || "GET".equals(method) && path.matches(run))) {
            throw new IllegalArgumentException("Unsupported runtime operation");
        }
        if (body == null || body.length > 8192 || "GET".equals(method) && body.length != 0) {
            throw new IllegalArgumentException("Invalid runtime reference body");
        }
        byte[] wire = body.clone();
        String credential = credential(secret, Instant.now().plusSeconds(30).getEpochSecond(), method, path, tenant, wire);
        return HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(10))
                .header("X-Tenant-Code", tenant)
                .header(AiRequestSigner.HEADER, credential)
                .header("Content-Type", "application/json")
                .method(method, wire.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(wire))
                .build();
    }

    static String credential(String secret, long expires, String method, String rawPath, String tenant, byte[] body) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("The AI runtime service credential is not configured");
        }
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")) {
            throw new IllegalArgumentException("Expected a business tenant");
        }
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = String.join("\n", "supportflow-ai-runtime:v1", "customer", Long.toString(expires),
                    method, rawPath, "", tenant, digest);
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "runtime:customer:" + expires + ":" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Could not sign AI runtime request", error);
        }
    }
}
