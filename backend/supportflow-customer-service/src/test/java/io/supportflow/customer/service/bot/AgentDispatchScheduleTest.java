package io.supportflow.customer.service.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentDispatchScheduleTest {
    private AnnotationConfigApplicationContext app(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
        context.register(PropertySourcesPlaceholderConfigurer.class, AgentDispatchSchedule.class, AgentShadowAdmission.class);
        context.registerBean(AgentEventOutbox.class, () -> mock(AgentEventOutbox.class));
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        return context;
    }

    @Test void noSchedulerWithDefaultOrExplicitOffConfiguration() {
        for (Map<String, Object> fixture : java.util.List.<Map<String, Object>>of(Map.of(),
                Map.of("supportflow.bot.runtime-dispatch-enabled", "false", "supportflow.bot.runtime-dispatch-tenant", "TENANT_A"))) {
            try (var context = app(fixture)) {
                context.refresh();
                assertTrue(context.getBeansOfType(AgentDispatchSchedule.class).isEmpty());
                assertTrue(context.getBeansOfType(AgentShadowAdmission.class).isEmpty());
            }
        }
    }

    @Test void requiresRlsExplicitTenantAndServiceCredential() {
        Map<String, Object> enabled = Map.of("supportflow.bot.runtime-dispatch-enabled", "true", "supportflow.tenant.rls-enabled", "true",
                "supportflow.bot.runtime-dispatch-tenant", "TENANT_A", "SUPPORTFLOW_AI_CUSTOMER_SECRET", "synthetic-config-credential-01-123456");
        for (Map.Entry<String, String> invalid : Map.of("supportflow.bot.runtime-dispatch-tenant", "PLATFORM",
                "supportflow.tenant.rls-enabled", "false", "SUPPORTFLOW_AI_CUSTOMER_SECRET", "").entrySet()) {
            var fixture = new HashMap<>(enabled);
            fixture.put(invalid.getKey(), invalid.getValue());
            try (var context = app(fixture)) {
                assertThrows(BeanCreationException.class, context::refresh);
            }
        }
    }

    @Test void shadowEnrollmentRequiresTheExplicitDispatcher() {
        try (var context = app(Map.of("supportflow.bot.runtime-shadow-admission-enabled", "true"))) {
            assertThrows(BeanCreationException.class, context::refresh);
        }
    }
}
