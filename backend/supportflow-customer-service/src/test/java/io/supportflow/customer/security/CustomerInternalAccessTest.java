package io.supportflow.customer.security;

import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.auth.CustomerInternalCredential.Caller;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.controller.internal.InternalNotificationController;
import io.supportflow.customer.controller.internal.InternalTenantSchemaController;
import io.supportflow.customer.controller.session.SessionInternalController;
import io.supportflow.customer.dto.internal.PublishBody;
import io.supportflow.customer.dto.internal.PublishResultVO;
import io.supportflow.customer.entity.Session;
import io.supportflow.customer.service.notification.NotificationBroadcastService;
import io.supportflow.customer.service.session.SessionService;
import io.supportflow.customer.service.tenant.TenantSchemaUpgradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

class CustomerInternalAccessTest {
    @Test void aRuntimeAnnotationAndAHeaderCannotForgeTheVerifiedRequestAttribute() throws Exception {
        var access = new CustomerInternalAccess(KEYS.get(Caller.REALTIME), KEYS.get(Caller.OPS), KEYS.get(Caller.TENANT));
        var controller = new io.supportflow.customer.controller.internal.RuntimeSourceController(
                mock(org.springframework.jdbc.core.JdbcTemplate.class), mock(org.springframework.transaction.PlatformTransactionManager.class));
        var handler = new org.springframework.web.method.HandlerMethod(controller,
                controller.getClass().getMethod("read", RuntimeCustomerCall.class, java.util.UUID.class, java.util.UUID.class));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(RuntimeCustomerCall.ATTRIBUTE, "forged");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(access.interceptor().preHandle(request, response, handler)); assertEquals(401, response.getStatus());
    }
    private static final String PREFIX = "/api/customer/internal";
    private static final String SESSION = PREFIX + "/sessions/test-session";
    private static final String PUBLISH = PREFIX + "/notifications/publish";
    private static final Map<Caller, String> KEYS = Map.of(
            Caller.REALTIME, "test-only-realtime-credential-000000000",
            Caller.OPS, "test-only-operations-credential-000000000",
            Caller.TENANT, "test-only-tenant-credential-0000000000000");
    private SessionService sessions;
    private NotificationBroadcastService notifications;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        sessions = mock(SessionService.class);
        notifications = mock(NotificationBroadcastService.class);
        var access = new CustomerInternalAccess(KEYS.get(Caller.REALTIME), KEYS.get(Caller.OPS), KEYS.get(Caller.TENANT));
        mvc = MockMvcBuilders.standaloneSetup(new SessionInternalController(sessions),
                        new InternalNotificationController(notifications),
                        new InternalTenantSchemaController(mock(TenantSchemaUpgradeService.class)),
                        new UnclassifiedEndpoint())
                .setControllerAdvice(new CustomerInternalAccess.MismatchHandler())
                .addInterceptors(access.interceptor()).build();
    }

    @Test
    void onlySignedCorrectServiceAndLiveCredentialCanAccessInternalController() throws Exception {
        String target = SESSION + "?tenantCode=TENANT_A";
        assertEquals(401, status(request(HttpMethod.GET, URI.create(target))
                .header(TenantContext.HEADER_TENANT_CODE, "TENANT_A")));
        assertEquals(401, status(signed(HttpMethod.GET, target, Caller.OPS, "TENANT_A", Clock.systemUTC())));
        var old = Clock.fixed(Instant.now().minus(Duration.ofMinutes(2)), ZoneOffset.UTC);
        assertEquals(401, status(signed(HttpMethod.GET, target, Caller.REALTIME, "TENANT_A", old)));
        assertEquals(404, status(request(HttpMethod.GET, URI.create(PREFIX + "/new-route"))));
        verifyNoInteractions(sessions);
    }

    @Test
    void queryTenantMustMatchSignedScopeAndLegitimateReadBindsScopeBeforeService() throws Exception {
        when(sessions.requireSession("TENANT_A", "test-session")).thenAnswer(invocation -> {
            assertEquals("TENANT_A", TenantContext.get());
            Session result = new Session();
            result.setSessionNo("test-session");
            result.setTenantCode("TENANT_A");
            return result;
        });
        String target = SESSION + "?tenantCode=TENANT_A";
        assertEquals(200, status(signed(HttpMethod.GET, target, Caller.REALTIME, "TENANT_A", Clock.systemUTC())));
        assertEquals(403, status(signed(HttpMethod.GET, SESSION + "?tenantCode=TENANT_B",
                Caller.REALTIME, "TENANT_A", Clock.systemUTC())));
        verify(sessions, times(1)).requireSession("TENANT_A", "test-session");
        verifyNoMoreInteractions(sessions);
        assertNull(TenantContext.get());
    }

    @Test
    void bodyTenantMustMatchAndScopedOperationCannotUsePlatformValue() throws Exception {
        when(notifications.publish(any())).thenAnswer(invocation -> {
            PublishBody body = invocation.getArgument(0);
            assertEquals(body.tenantCode(), TenantContext.get());
            return new PublishResultVO(true, null);
        });
        assertEquals(403, status(publish("TENANT_A", "TENANT_B")));
        assertEquals(403, status(publish("PLATFORM", "PLATFORM")));
        verifyNoInteractions(notifications);
        assertEquals(200, status(publish("TENANT_A", "TENANT_A")));
        verify(notifications, times(1)).publish(any(PublishBody.class));
        assertNull(TenantContext.get());
    }

    @Test
    void globalOperationAllowsOnlyItsOwnCallerAndDoesNotAcceptTenantScope() throws Exception {
        String catalog = PREFIX + "/tenant-schema/catalog";
        assertEquals(200, status(signed(HttpMethod.GET, catalog, Caller.TENANT, null, Clock.systemUTC())));
        assertEquals(403, status(signed(HttpMethod.GET, catalog, Caller.TENANT, "TENANT_A", Clock.systemUTC())));
        assertEquals(401, status(signed(HttpMethod.GET, catalog, Caller.REALTIME, null, Clock.systemUTC())));
    }

    private MockHttpServletRequestBuilder publish(String scope, String bodyTenant) {
        return signed(HttpMethod.POST, PUBLISH, Caller.OPS, scope, Clock.systemUTC())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantCode\":\"" + bodyTenant + "\",\"title\":\"Test notice\"}");
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder signed(HttpMethod method, String target, Caller caller,
                                                        String tenant, Clock clock) {
        URI uri = URI.create(target);
        String signed = CustomerInternalCredential.sign(KEYS.get(caller), caller, method.name(), uri.getRawPath(),
                uri.getRawQuery(), tenant, clock);
        var req = request(method, uri).header(CustomerInternalCredential.HEADER, signed);
        if (tenant != null) {
            req.header(TenantContext.HEADER_TENANT_CODE, tenant);
        }
        return req;
    }

    @RestController
    static class UnclassifiedEndpoint {
        @GetMapping(PREFIX + "/new-route")
        String unclassified() {
            return "this handler must never execute";
        }
    }
}
