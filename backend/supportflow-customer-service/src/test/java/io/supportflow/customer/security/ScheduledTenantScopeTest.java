package io.supportflow.customer.security;

import io.supportflow.common.auth.LoginUser;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.internal.TenantListClient;
import io.supportflow.customer.internal.UserRoleClient;
import io.supportflow.customer.mapper.*;
import io.supportflow.customer.service.customer.CustomerTagRuleService;
import io.supportflow.customer.service.metrics.MetricsService;
import io.supportflow.customer.service.quality.RealtimeQaService;
import io.supportflow.customer.service.session.RoutingService;
import io.supportflow.customer.service.ticket.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledTenantScopeTest {
    @Mock TenantListClient tenants;
    @Mock SessionMapper sessions;
    @Mock AgentDailyMetricMapper metrics;
    @Mock CustomerTagDefMapper rules;
    @Mock TicketMapper tickets;
    @Mock UserRoleClient roles;

    @InjectMocks RoutingService routing;
    @InjectMocks RealtimeQaService qa;
    @InjectMocks MetricsService reporting;
    @InjectMocks CustomerTagRuleService tags;
    @InjectMocks TicketService sla;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "test"),
                io.supportflow.customer.entity.Ticket.class);
        mocks = MockitoAnnotations.openMocks(this);
        when(tenants.allTenantCodes()).thenReturn(List.of("TENANT_A", "TENANT_B"));
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void routingAndQaBindTenantBeforeQueryAndContinueAfterFirstTenantFails() {
        when(sessions.selectQueuedSessions(anyString(), anyInt())).thenAnswer(call -> {
            assertEquals(call.getArgument(0, String.class), TenantContext.get());
            if ("TENANT_A".equals(TenantContext.get())) throw new IllegalStateException("fixture failure");
            return List.of();
        });
        when(sessions.selectUnansweredSessions(anyString(), any(), anyInt())).thenAnswer(call -> {
            assertEquals(call.getArgument(0, String.class), TenantContext.get());
            if ("TENANT_A".equals(TenantContext.get())) throw new IllegalStateException("fixture failure");
            return List.of();
        });
        routing.dispatchQueue();
        assertEquals(0, qa.scanUnansweredSessions().alerted());
        verify(sessions).selectQueuedSessions(eq("TENANT_B"), anyInt());
        verify(sessions).selectUnansweredSessions(eq("TENANT_B"), any(), anyInt());
        assertNull(TenantContext.get());
    }

    @Test
    void historyBackfillAndTagRulesOpenEachTenantScope() {
        var days = new ArrayList<String>();
        var tagLookups = new ArrayList<String>();
        when(metrics.rebuildDaily(anyString(), any(), any(), any())).thenAnswer(call -> {
            assertEquals(call.getArgument(0, String.class), TenantContext.get());
            days.add(TenantContext.get());
            return 0;
        });
        when(rules.selectList(any())).thenAnswer(call -> {
            tagLookups.add(TenantContext.get());
            return List.of();
        });
        reporting.warmUpHistory();
        reporting.scheduledRebuild();
        tags.scheduledScan();
        assertEquals(16, days.stream().filter("TENANT_A"::equals).count());
        assertEquals(16, days.stream().filter("TENANT_B"::equals).count());
        assertEquals(List.of("TENANT_A", "TENANT_B"), tagLookups);
        assertNull(TenantContext.get());
    }

    @Test
    void manualSlaOnlyScansTheAuthorizedTenantWhileScheduledScanUsesBoth() {
        var tenantReads = new ArrayList<String>();
        when(roles.rolesOf(7L, "TENANT_B")).thenReturn(List.of("ADMIN"));
        when(tickets.selectList(any())).thenAnswer(call -> {
            tenantReads.add(TenantContext.get());
            return List.of();
        });
        sla.scanSla(new LoginUser(7L, "admin", "Admin", 2, "TENANT_B"));
        verifyNoInteractions(tenants);
        assertEquals(List.of("TENANT_B"), tenantReads);
        sla.scheduledSlaScan();
        assertEquals(List.of("TENANT_B", "TENANT_A", "TENANT_B"), tenantReads);
        assertNull(TenantContext.get());
    }
}
