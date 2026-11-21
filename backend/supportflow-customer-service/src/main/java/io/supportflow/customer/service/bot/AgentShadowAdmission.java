package io.supportflow.customer.service.bot;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.customer.event.SessionMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

import static io.supportflow.customer.service.session.SessionService.MSG_TYPE_TEXT;
import static io.supportflow.customer.service.session.SessionService.SENDER_CUSTOMER;
import static io.supportflow.customer.service.session.SessionService.VISIBLE_ALL;

/** Opt-in enrollment for the read-only Worker, without changing visitor reply routing. */
@Component
@ConditionalOnProperty(name = "supportflow.bot.runtime-shadow-admission-enabled", havingValue = "true")
public final class AgentShadowAdmission {
    private static final Logger log = LoggerFactory.getLogger(AgentShadowAdmission.class);
    private final AgentEventOutbox outbox;
    private final TransactionTemplate savepoint;
    private final String tenant;

    public AgentShadowAdmission(AgentEventOutbox outbox, PlatformTransactionManager manager, AgentDispatchSchedule schedule) {
        this.outbox = outbox;
        this.tenant = schedule.tenant();
        savepoint = new TransactionTemplate(manager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @EventListener
    public void record(SessionMessageEvent message) {
        String content = message.content();
        if (!tenant.equals(message.tenantCode()) || !tenant.equals(TenantContext.get())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || message.senderType() != SENDER_CUSTOMER || message.msgType() != MSG_TYPE_TEXT || message.visibleTo() != VISIBLE_ALL
                || message.messageId() < 1 || content == null || content.length() > 8192 || content.isBlank()
                || content.getBytes(StandardCharsets.UTF_8).length > 8192) {
            return;
        }
        try {
            savepoint.executeWithoutResult(status -> outbox.enqueueVisitorText(tenant, message.messageId(), 500));
        } catch (BizException rejected) {
            if (rejected.getCode() != 40401) log.warn("Agent shadow admission skipped code={}", rejected.getCode());
        } catch (DataAccessException | TransactionException failure) {
            log.warn("Agent shadow admission unavailable errorType={}", failure.getClass().getSimpleName());
        }
    }
}
