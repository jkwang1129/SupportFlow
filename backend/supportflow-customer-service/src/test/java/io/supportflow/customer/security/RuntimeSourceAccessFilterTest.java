package io.supportflow.customer.security;

import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.supportflow.customer.testkit.RuntimeSourceWire.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSourceAccessFilterTest {
    static final long NOW = 1700000000;
    private RuntimeSourceAccessFilter filter(boolean enabled) { return new RuntimeSourceAccessFilter(KEY, enabled, List.of(), Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)); }
    private MockHttpServletRequest request(String method, String target, String tenant, byte[] body, long expiry) {
        var request = new MockHttpServletRequest(method, URI.create(target).getRawPath());
        request.setQueryString(URI.create(target).getRawQuery()); request.setContent(body);
        request.addHeader("X-Tenant-Code", tenant);
        request.addHeader(CustomerInternalCredential.HEADER, credential(method, target, tenant, body, expiry));
        if (method.equals("POST")) request.setContentType("application/json");
        return request;
    }
    private int reject(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse(); var called = new AtomicBoolean();
        filter(true).doFilter(request, response, (a,b) -> called.set(true));
        assertFalse(called.get()); assertNull(request.getAttribute(RuntimeCustomerCall.ATTRIBUTE));
        return response.getStatus();
    }

    @Test void fixedPythonFixturePassesAndAuthorityIsBoundOnlyAroundBusinessWork() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String expected = "runtime:ai:1700000020:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a:5ac0feea6a414654abe7d1a77056856caa9a75997b5fc3db6bc65486bbda7b9d";
        assertEquals(expected, credential("POST", PATH, "TENANT_A", body, NOW+20));
        var request = request("POST", PATH, "TENANT_A", body, NOW+20);
        var called = new AtomicBoolean();
        filter(true).doFilter(request, new MockHttpServletResponse(), (a,b) -> {
            var call = (RuntimeCustomerCall) a.getAttribute(RuntimeCustomerCall.ATTRIBUTE);
            assertNull(TenantContext.get());
            call.inTenant(call.eventId(), call.runId(), () -> { assertEquals("TENANT_A", TenantContext.get()); called.set(true); return null; });
            assertNull(TenantContext.get());
        });
        assertTrue(called.get());
    }

    @Test void anonymousAndInvalidSignaturesCannotTriggerBodyReads() throws Exception {
        var request = new MockHttpServletRequest("POST", PATH) {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("Unauthorized body must never be read"); }
        };
        assertEquals(401, reject(request));
        request.addHeader("X-Tenant-Code", "TENANT_A"); request.addHeader(CustomerInternalCredential.HEADER, "fake");
        assertEquals(401, reject(request));
    }

    @Test void signatureBindsMethodRawPathQueryTenantBodyAndExpiry() throws Exception {
        for (String tamper : new String[]{"method","path","query","tenant","body","expired","far-future"}) {
            var request = request("POST", PATH, "TENANT_A", new byte[]{'{','}'}, NOW+20);
            switch (tamper) {
                case "method" -> request.setMethod("GET");
                case "path" -> request.setRequestURI(PATH.replace("000000000001", "000000000003"));
                case "query" -> request.setQueryString("extra=1");
                case "tenant" -> { request.removeHeader("X-Tenant-Code"); request.addHeader("X-Tenant-Code", "TENANT_B"); }
                case "body" -> request.setContent("{ }".getBytes(StandardCharsets.UTF_8));
                case "expired" -> request = request("POST", PATH, "TENANT_A", "{}".getBytes(StandardCharsets.UTF_8), NOW);
                case "far-future" -> request = request("POST", PATH, "TENANT_A", "{}".getBytes(StandardCharsets.UTF_8), NOW+36);
            }
            assertEquals(401, reject(request), tamper);
        }
    }

    @Test void duplicateAuthAndTenantHeadersFailClosed() throws Exception {
        var request = request("GET", PATH, "TENANT_A", new byte[0], NOW+20);
        request.addHeader("X-Tenant-Code", "TENANT_A"); assertEquals(401, reject(request));
        request = request("GET", PATH, "TENANT_A", new byte[0], NOW+20);
        request.addHeader(CustomerInternalCredential.HEADER, request.getHeader(CustomerInternalCredential.HEADER));
        assertEquals(401, reject(request));
    }

    @Test void oldProtocolsAndOtherRolesCannotAuthorizeRuntimeSourceOperations() throws Exception {
        for (String credential : new String[]{NOW+20 + ":" + "0".repeat(64), "runtime:customer:"+(NOW+20)+":"+"0".repeat(64),
                credential("GET",PATH,"TENANT_A",new byte[0],NOW+20).replace("runtime:ai:","runtime:ops:")}) {
            var request = request("GET", PATH, "TENANT_A", new byte[0], NOW+20);
            request.removeHeader(CustomerInternalCredential.HEADER); request.addHeader(CustomerInternalCredential.HEADER, credential);
            assertEquals(401, reject(request));
        }
    }

    @Test void onlyCanonicalSnapshotMethodsAndBusinessTenantsArePermitted() throws Exception {
        for (String tenant : new String[]{"PLATFORM","platform","","A".repeat(17),"中文"})
            assertEquals(403, reject(request("GET", PATH, tenant, new byte[0], NOW+20)));
        assertEquals(403, reject(request("DELETE", PATH, "TENANT_A", new byte[0], NOW+20)));
        assertEquals(403, reject(request("GET", PATH+"?extra=1", "TENANT_A", new byte[0], NOW+20)));
        assertEquals(403, reject(request("GET", PATH.replace("/snapshot", "/unknown"), "TENANT_A", new byte[0], NOW+20)));
        assertEquals(403, reject(request("GET", PATH.replace("/events/", "/%65vents/"), "TENANT_A", new byte[0], NOW+20)));
    }

    @Test void disabledSourceApiRejectsWithoutReadingAnyBody() throws Exception {
        var request = new MockHttpServletRequest("POST", PATH) {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("Disabled source must not read a body"); }
        };
        request.addHeader("X-Tenant-Code", "TENANT_A");
        request.addHeader(CustomerInternalCredential.HEADER, credential("POST", PATH, "TENANT_A", "{}".getBytes(StandardCharsets.UTF_8), NOW+20));
        var response = new MockHttpServletResponse();
        filter(false).doFilter(request, response, (a,b) -> fail("Disabled source cannot reach the controller"));
        assertEquals(503, response.getStatus()); assertEquals("5", response.getHeader("Retry-After"));
    }

    @Test void boundedBodiesStrictJsonAndContentTypeAreEnforced() throws Exception {
        assertEquals(413, reject(request("POST", PATH, "TENANT_A", new byte[8193], NOW+20)));
        assertEquals(400, reject(request("GET", PATH, "TENANT_A", "{}".getBytes(StandardCharsets.UTF_8), NOW+20)));
        for (String body : new String[]{"[]", "{", "{}{}", "{\"tenant_code\":\"TENANT_A\"}", "{\"x\":1,\"x\":2}"})
            assertEquals(400, reject(request("POST", PATH, "TENANT_A", body.getBytes(StandardCharsets.UTF_8), NOW+20)));
        var request = request("POST", PATH, "TENANT_A", "{}".getBytes(StandardCharsets.UTF_8), NOW+20);
        request.setContentType("text/plain"); assertEquals(415, reject(request));
        request = request("POST", PATH, "TENANT_A", "{}".getBytes(StandardCharsets.UTF_8), NOW+20);
        request.addHeader("Content-Type", "text/plain"); assertEquals(415, reject(request));
    }

    @Test void sourceCredentialCannotBeMissingWhenEnabledOrSharedWithOtherCallers() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeSourceAccessFilter("short",true,List.of(),Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeSourceAccessFilter(KEY,true,List.of(KEY),Clock.systemUTC()));
    }
}
