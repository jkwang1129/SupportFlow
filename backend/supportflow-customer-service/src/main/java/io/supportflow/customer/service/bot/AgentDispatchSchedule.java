package io.supportflow.customer.service.bot;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Delivers persisted source events only for the tenant assigned by the operator. */
@Component
@ConditionalOnProperty(name = "supportflow.bot.runtime-dispatch-enabled", havingValue = "true")
public final class AgentDispatchSchedule {
    private static final Logger log = LoggerFactory.getLogger(AgentDispatchSchedule.class);
    private final String tenant;
    private final AgentRuntimeClient client;
    private final AgentEventDispatcher dispatcher;
    private final ScheduledExecutorService scheduler;

    public AgentDispatchSchedule(
            AgentEventOutbox outbox, PlatformTransactionManager manager,
            @Value("${supportflow.bot.runtime-dispatch-tenant:}") String tenant,
            @Value("${supportflow.tenant.rls-enabled:false}") boolean rlsEnabled,
            @Value("${supportflow.ai.bot-base-url:http://127.0.0.1:9100}") String aiBase,
            @Value("${SUPPORTFLOW_AI_CUSTOMER_SECRET:}") String secret) {
        if (!rlsEnabled || tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}")
                || tenant.equalsIgnoreCase("PLATFORM")) {
            throw new IllegalArgumentException("Runtime dispatch requires RLS and an explicit business tenant");
        }
        this.tenant = tenant;
        URI endpoint = URI.create(aiBase.replaceAll("/+$", "") + "/api/ai/v1/runtime/runs");
        client = new AgentRuntimeClient(endpoint, secret, Duration.ofSeconds(3));
        dispatcher = new AgentEventDispatcher(outbox, manager, client);
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "agent-event-dispatch"));
    }

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::deliver, 1, 1, TimeUnit.SECONDS);
    }

    private void deliver() {
        try {
            dispatcher.dispatchOne(tenant);
        } catch (RuntimeException failure) {
            log.warn("Agent event dispatch interrupted by failure type={}", failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
        client.close();
    }
}
