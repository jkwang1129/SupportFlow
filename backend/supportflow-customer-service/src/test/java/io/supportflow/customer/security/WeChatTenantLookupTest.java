package io.supportflow.customer.security;

import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.entity.WeChatChannelConfig;
import io.supportflow.customer.mapper.WeChatChannelConfigMapper;
import io.supportflow.customer.wechat.WeChatConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeChatTenantLookupTest {
    @Test
    void inboundCallbackBindsResolvedConfigurationTenantBeforeWritingDedupeLog() {
        var configs = mock(WeChatConfigService.class);
        var logs = mock(io.supportflow.customer.mapper.WeChatInboundLogMapper.class);
        var bindings = mock(io.supportflow.customer.mapper.WeChatCustomerBindMapper.class);
        when(bindings.selectOne(any())).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            return null;
        });
        when(configs.byAppId("fixture-app")).thenReturn(WeChatChannelConfig.builder()
                .tenantCode("TENANT_A").appId("fixture-app").build());
        when(logs.insert(any(io.supportflow.customer.entity.WeChatInboundLog.class))).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            var row = call.getArgument(0, io.supportflow.customer.entity.WeChatInboundLog.class);
            assertEquals("TENANT_A", row.getTenantCode());
            return 1;
        });
        var service = new io.supportflow.customer.wechat.WeChatInboundService(configs, logs,
                bindings, null, null, null, new io.supportflow.common.id.SnowflakeIdGenerator(1), null, null);
        String body = "<xml><FromUserName>visitor</FromUserName><MsgType>event</MsgType>"
                + "<Event>unsubscribe</Event><CreateTime>100</CreateTime></xml>";
        assertEquals("success", service.handle("fixture-app", body, false));
        verify(logs).insert(any(io.supportflow.customer.entity.WeChatInboundLog.class));
        assertNull(TenantContext.get());
    }

    @Test
    void configurationLookupBindsResolvedTenantAndCannotSwitchAuthenticatedTenant() {
        var tenants = mock(ChannelTenantResolver.class);
        var configs = mock(WeChatChannelConfigMapper.class);
        var config = WeChatChannelConfig.builder().tenantCode("TENANT_A").appId("fixture-app").build();
        when(tenants.wechatTenant("fixture-app")).thenReturn("TENANT_A");
        when(configs.selectOne(any())).thenAnswer(call -> {
            assertEquals("TENANT_A", TenantContext.get());
            return config;
        });
        var service = new WeChatConfigService(configs, null, null, tenants);
        assertSame(config, service.byAppId("fixture-app"));
        clearInvocations(configs);
        TenantContext.run("TENANT_B", () -> {
            assertNull(service.byAppId("fixture-app"));
            assertEquals("TENANT_B", TenantContext.get());
        });
        verifyNoInteractions(configs);
        assertNull(TenantContext.get());
    }
}
