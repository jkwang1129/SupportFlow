package io.supportflow.customer.nativechannel;

import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.entity.Channel;
import io.supportflow.customer.internal.TenantListClient;
import io.supportflow.customer.mapper.ChannelMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailInboundTenantTest {
    @Test
    void channelLookupAndConfigurationStayInsideTenantScopeWithoutConnectingToMailbox() {
        var channels = mock(ChannelMapper.class);
        var config = mock(ChannelNativeConfigService.class);
        var tenants = mock(TenantListClient.class);
        when(tenants.allTenantCodes()).thenReturn(List.of("TENANT_A", "TENANT_B"));
        when(channels.selectList(any())).thenAnswer(call -> {
            String tenant = TenantContext.get();
            assertNotNull(tenant);
            return List.of(Channel.builder().id(1L).tenantCode(tenant).build());
        });
        when(config.raw(anyString(), eq(1L))).thenAnswer(call -> {
            assertEquals(call.getArgument(0, String.class), TenantContext.get());
            return Map.of();
        });
        new MailInboundPoller(null, config, channels, 20, tenants).poll();
        verify(config).raw("TENANT_A", 1L);
        verify(config).raw("TENANT_B", 1L);
        assertNull(TenantContext.get());
    }
}
