package io.supportflow.gateway.filter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiGatewayAccessTest {
    private static final String SECRET = "generated-only-for-ai-gateway-test-signatures-123456789";
    private static final String AI_SECRET = "generated-only-for-ai-upstream-credentials-987654321";
    private static final HttpServer UPSTREAM = upstream();
    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("SUPPORTFLOW_AI_SERVICE_URL", AiGatewayAccessTest::upstreamUrl);
        registry.add("SUPPORTFLOW_CUSTOMER_SERVICE_URL", AiGatewayAccessTest::upstreamUrl);
        registry.add("SUPPORTFLOW_TENANT_SERVICE_URL", AiGatewayAccessTest::upstreamUrl);
        registry.add("SUPPORTFLOW_JWT_SECRET", () -> SECRET);
        registry.add("SUPPORTFLOW_AI_GATEWAY_SECRET", () -> AI_SECRET);
        registry.add("SUPPORTFLOW_TENANT_RATE_LIMIT_ENABLED", () -> "false");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Test
    void publicHealthAndExistingVisitorRoutesStillPassWithoutLogin() throws Exception {
        assertForwarded(send("GET", "/api/ai/health", null, null), "/api/ai/health|");
        assertForwarded(send("GET", "/api/customer/visitor/example", null, null),
                "/api/customer/visitor/example|");
    }

    @Test
    void internalKnowledgeAndModelEndpointsAreNeverForwardedByGateway() throws Exception {
        String token = validTenantToken();
        assertDenied(404, send("POST", "/api/ai/v1/kb/index", null, null));
        assertDenied(404, send("GET", "/api/ai/v1/kb/health", null, null));
        assertDenied(404, send("GET", "/api/ai/v1/kb/documents/10/chunks", token, "TENANT_A"));
        assertDenied(404, send("DELETE", "/api/ai/v1/kb/documents/10", token, "TENANT_A"));
        assertDenied(404, send("POST", "/api/ai/v1/agent/brain", token, "TENANT_A"));
        assertDenied(404, send("POST", "/api/ai/v1/bot/settings", token, "TENANT_A"));
    }

    @Test
    void customerServiceBoundariesAreNotBridgedThroughPublicRoutes() throws Exception {
        assertDenied(404, send("POST", "/api/customer/internal/sessions/example/messages", null, null));
        assertDenied(404, send("GET", "/api/customer/internal/sessions/example", validTenantToken(), "TENANT_A"));
    }

    @Test
    void consoleNeedsAValidUnexpiredEnterpriseLogin() throws Exception {
        String path = "/api/ai/v1/bot/settings";
        assertDenied(401, send("GET", path, null, "TENANT_A"));
        assertDenied(401, send("GET", path, "invalid", "TENANT_A"));
        assertDenied(401, send("GET", path, token("TENANT_A", 2, Instant.now().minusSeconds(5)), "TENANT_A"));
        assertDenied(401, send("GET", path, token("TENANT_A", 2, null), "TENANT_A"));
        assertDenied(403, send("GET", path, token("PLATFORM", 1, validUntil()), "PLATFORM"));
    }

    @Test
    void consoleUsesVerifiedTenantAndRejectsConflictingHeader() throws Exception {
        String token = validTenantToken();
        assertForwarded(send("GET", "/api/ai/v1/kb/status", token, null), "/api/ai/v1/kb/status|TENANT_A");
        assertForwarded(send("PUT", "/api/ai/v1/bot/settings", token, null), "/api/ai/v1/bot/settings|TENANT_A");
        assertForwarded(send("POST", "/api/ai/v1/bot/training/versions/v1.2/publish", token, "TENANT_A"),
                "/api/ai/v1/bot/training/versions/v1.2/publish|TENANT_A");
        assertDenied(403, send("GET", "/api/ai/v1/kb/status", token, "TENANT_B"));
    }

    private HttpResponse<String> send(String method, String path, String token, String tenant)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token)
                .header("X-SupportFlow-AI-Auth", "caller-supplied");
        if (tenant != null) request.header("X-Tenant-Code", tenant);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertDenied(int expectedStatus, HttpResponse<String> response) {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("X-Test-Upstream").isEmpty());
    }

    private static void assertForwarded(HttpResponse<String> response, String body) {
        assertEquals(200, response.statusCode());
        assertEquals(body, response.body());
        assertEquals("true", response.headers().firstValue("X-Test-Upstream").orElseThrow());
        assertTrue(response.headers().firstValue("X-Test-Login-Header").isEmpty());
        if (body.endsWith("|TENANT_A")) {
            assertTrue(response.headers().firstValue("X-Test-AI-Header").orElseThrow().startsWith("gateway:"));
        } else {
            assertTrue(response.headers().firstValue("X-Test-AI-Header").isEmpty());
        }
    }

    private static String validTenantToken() throws Exception {
        return token("TENANT_A", 2, validUntil());
    }

    private static Instant validUntil() {
        return Instant.now().plusSeconds(120);
    }

    private static String token(String tenant, int type, Instant expiration) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"123\",\"name\":\"AI regression account\",\"type\":" + type
                + ",\"tnt\":\"" + tenant + "\""
                + (expiration == null ? "" : ",\"exp\":" + expiration.getEpochSecond()) + "}";
        String data = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return data + "." + encoder.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String upstreamUrl() {
        return "http://127.0.0.1:" + UPSTREAM.getAddress().getPort();
    }

    private static HttpServer upstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Code");
                byte[] body = (path + "|" + (tenant == null ? "" : tenant)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("X-Test-Upstream", "true");
                for (var forward : new String[][]{
                        {"Authorization", "X-Test-Login-Header"}, {"X-SupportFlow-AI-Auth", "X-Test-AI-Header"}}) {
                    String value = exchange.getRequestHeaders().getFirst(forward[0]);
                    if (value != null) exchange.getResponseHeaders().set(forward[1], value);
                }
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
