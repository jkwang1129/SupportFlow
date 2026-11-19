package io.supportflow.customer.nativechannel;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.security.ChannelTenantResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeWebhookTenantTest {
    @Test
    void verifiesSignedCallbackInsideResolvedScopeBeforeStartingBusinessWork() {
        var config = mock(ChannelNativeConfigService.class);
        var inbound = mock(ChannelNativeInboundService.class);
        var tenants = mock(ChannelTenantResolver.class);
        var channel = Channel.builder().id(1L).tenantCode("TENANT_A").channelType(4).build();
        when(tenants.resolve("channel-key")).thenReturn("TENANT_A");
        when(config.channelByAppKey("channel-key")).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            return channel;
        });
        when(config.raw("TENANT_A", 1L)).thenReturn(Map.of("pushSecret", "fixture-webhook-secret"));
        when(inbound.appMessage(eq(channel), anyString(), any(), anyString(), any())).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            return null;
        });
        var service = new ChannelNativeWebhookService(config, inbound, null, tenants);
        String body = "{\"userId\":\"visitor\",\"content\":\"Hello\"}";
        String timestamp = Long.toString(System.currentTimeMillis());
        service.appMessage("channel-key", timestamp, NativeSignature.sign("fixture-webhook-secret", timestamp, body), body);
        verify(inbound).appMessage(eq(channel), eq("visitor"), isNull(), eq("Hello"), isNull());
        clearInvocations(inbound);
        when(config.raw("TENANT_A", 1L)).thenReturn(Map.of());
        assertThrows(BizException.class, () -> service.appMessage("channel-key", null, null, body));
        verifyNoInteractions(inbound);
        assertNull(TenantContext.get());
    }

    @Test
    void signatureRequiresConfiguredKeyValidTimeAndMatchingContent() {
        long now = System.currentTimeMillis();
        String timestamp = Long.toString(now);
        String signature = NativeSignature.sign("fixture-key", timestamp, "message");
        assertTrue(NativeSignature.verify("fixture-key", timestamp, signature, "message", now));
        assertFalse(NativeSignature.verify("", timestamp, signature, "message", now));
        assertFalse(NativeSignature.verify(null, timestamp, signature, "message", now));
        assertFalse(NativeSignature.verify("fixture-key", timestamp, signature, "different", now));
        assertFalse(NativeSignature.verify("fixture-key", timestamp, signature, "message", now + 301_000));
        assertFalse(NativeSignature.verify("fixture-key", timestamp, "malformed", "message", now));
    }
}
