package io.supportflow.customer.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.supportflow.common.billing.UsageBatch;
import io.supportflow.common.context.TenantContext;
import io.supportflow.customer.internal.BillingUsageClient;
import io.supportflow.customer.internal.TenantListClient;
import io.supportflow.customer.mapper.AgentStatusMapper;
import io.supportflow.customer.mapper.FileMetaMapper;
import io.supportflow.customer.service.metrics.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_USAGE_TEST_CUSTOMER_URL", matches=".+")
class BillingUsageOutboxPostgresTest {
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private BillingUsageOutbox outbox;
    private String tenant;

    @BeforeEach void setup() {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("SUPPORTFLOW_USAGE_TEST_CUSTOMER_URL"));
        config.setUsername("usage_test_customer");
        config.setPassword(System.getenv("SUPPORTFLOW_USAGE_TEST_CUSTOMER_PASSWORD"));
        config.setMaximumPoolSize(5);
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(new TenantRlsConfig.TenantRlsDataSource(pool));
        outbox = new BillingUsageOutbox(jdbc);
        tenant = "OB" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(false, jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
    }
    @AfterEach void close() { if (pool != null) pool.close(); }

    @Test void persistsAcrossStoreRecreationAndKeepsOnlyOnePendingBatchPerTenant() {
        UsageBatch original = batch(tenant), another = batch(tenant);
        assertTrue(outbox.enqueue(original));
        var restarted = new BillingUsageOutbox(jdbc);
        assertEquals(original, restarted.pending(tenant));
        assertTrue(restarted.enqueue(original));
        assertFalse(restarted.enqueue(another));
        assertEquals(original, restarted.pending(tenant));
        restarted.acknowledge(original);
        assertNull(restarted.pending(tenant));
        assertTrue(restarted.enqueue(another));
    }

    @Test void crossTenantAndUnboundDatabaseAccessFailClosed() {
        UsageBatch original = batch(tenant);
        outbox.enqueue(original);
        String other = "OTHER_" + tenant.substring(2);
        outbox.enqueue(batch(other));
        assertThrows(IllegalStateException.class, () -> TenantContext.bind(tenant, () -> outbox.pending(other)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM billing_usage_outbox", Integer.class));
        TenantContext.run(tenant, () -> assertEquals(List.of(tenant), jdbc.queryForList(
                "SELECT tenant_code FROM billing_usage_outbox", String.class)));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
                INSERT INTO billing_usage_outbox(batch_id,tenant_code,stat_date,sessions,messages,ai_calls,seats,storage_gb)
                VALUES (CAST(? AS uuid),?,CURRENT_DATE,0,0,0,0,0)
                """, UUID.randomUUID().toString(), tenant));
        outbox.acknowledge(new UsageBatch(original.batchId(), other, original.statDate(),
                original.sessions(), original.messages(), original.aiCalls(), original.seats(), original.storageGb()));
        assertEquals(original, outbox.pending(tenant));
    }

    @Test void competingProducersCannotCreateAnUnboundedPendingQueue() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return outbox.enqueue(batch(tenant)); });
            var second = workers.submit(() -> { start.await(); return new BillingUsageOutbox(jdbc).enqueue(batch(tenant)); });
            start.countDown();
            assertEquals(1, (first.get(5,TimeUnit.SECONDS) ? 1 : 0) + (second.get(5,TimeUnit.SECONDS) ? 1 : 0));
        }
        TenantContext.run(tenant, () -> assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM billing_usage_outbox", Integer.class)));
    }

    @Test void aPendingBatchCannotBeChangedUnderTheSameIdentity() {
        UsageBatch saved = batch(tenant);
        outbox.enqueue(saved);
        var changed = new UsageBatch(saved.batchId(),tenant,saved.statDate(),2,2,3,0,BigDecimal.ZERO);
        assertThrows(IllegalStateException.class,()->outbox.enqueue(changed));
        assertEquals(saved,outbox.pending(tenant));
    }

    @Test void lostRemoteResponseSurvivesReporterRestartWithTheSameIdAndDate() {
        var client = mock(BillingUsageClient.class);
        when(client.report(any())).thenReturn(false, true);
        var collector = new BillingUsageCollector();
        collector.countMessage(tenant);
        collector.countMessage(tenant);
        reporter(collector, client, outbox).flush();
        UsageBatch saved = outbox.pending(tenant);
        assertNotNull(saved);
        assertEquals(2, saved.messages());
        reporter(new BillingUsageCollector(), client, new BillingUsageOutbox(jdbc)).flush();
        assertNull(outbox.pending(tenant));
        var sent = ArgumentCaptor.forClass(UsageBatch.class);
        verify(client, times(2)).report(sent.capture());
        assertEquals(List.of(saved, saved), sent.getAllValues());
        assertNull(TenantContext.get());
    }

    @Test void successfulRemoteWriteButFailedLocalAckRetriesTheOriginalBatch() {
        var client = mock(BillingUsageClient.class);
        when(client.report(any())).thenReturn(true);
        var unreliable = spy(outbox);
        doThrow(new IllegalStateException("synthetic ack failure")).when(unreliable).acknowledge(any());
        reporter(new BillingUsageCollector(), client, unreliable).flush();
        UsageBatch saved = outbox.pending(tenant);
        assertNotNull(saved);
        reporter(new BillingUsageCollector(), client, outbox).flush();
        var sent = ArgumentCaptor.forClass(UsageBatch.class);
        verify(client, times(2)).report(sent.capture());
        assertEquals(List.of(saved,saved), sent.getAllValues());
        assertNull(outbox.pending(tenant));
    }

    @Test void unknownEnqueueCommitDoesNotRestoreUsageUnderANewBatchIdentity() {
        var first = new AtomicBoolean(true);
        var uncertain = new BillingUsageOutbox(jdbc) {
            @Override public boolean enqueue(UsageBatch batch) {
                boolean accepted = super.enqueue(batch);
                if (first.getAndSet(false)) throw new IllegalStateException("synthetic lost commit response");
                return accepted;
            }
        };
        var client = mock(BillingUsageClient.class);
        when(client.report(any())).thenReturn(true);
        var collector = new BillingUsageCollector();
        collector.countSession(tenant);
        var reporter = reporter(collector, client, uncertain);
        reporter.flush();
        UsageBatch saved = outbox.pending(tenant);
        assertNotNull(saved);
        verifyNoInteractions(client);
        reporter.flush();
        verify(client, times(1)).report(saved);
        assertNull(outbox.pending(tenant));
    }

    @Test void newCountersWaitBehindAFailedBatchThenGetTheirOwnIdentity() {
        var client = mock(BillingUsageClient.class);
        when(client.report(any())).thenReturn(false,false,true,true);
        var collector = new BillingUsageCollector();
        collector.countMessage(tenant);
        var reporter = reporter(collector,client,outbox);
        reporter.flush();
        collector.countMessage(tenant);
        collector.countMessage(tenant);
        collector.countMessage(tenant);
        reporter.flush();
        reporter.flush();
        var sent = ArgumentCaptor.forClass(UsageBatch.class);
        verify(client,times(4)).report(sent.capture());
        var batches = sent.getAllValues();
        assertEquals(batches.get(0),batches.get(1));
        assertEquals(batches.get(0),batches.get(2));
        assertNotEquals(batches.get(0).batchId(),batches.get(3).batchId());
        assertEquals(1,batches.get(0).messages());
        assertEquals(3,batches.get(3).messages());
        assertNull(outbox.pending(tenant));
    }

    private BillingUsageReporter reporter(BillingUsageCollector collector, BillingUsageClient client, BillingUsageOutbox store) {
        var seats = mock(AgentStatusMapper.class);
        var files = mock(FileMetaMapper.class);
        var tenants = mock(TenantListClient.class);
        when(seats.selectCount(any())).thenReturn(0L);
        when(files.sumSizeByTenant(tenant)).thenAnswer(call -> { assertEquals(tenant,TenantContext.get()); return 0L; });
        when(tenants.allTenantCodes()).thenReturn(List.of(tenant));
        return new BillingUsageReporter(collector,client,seats,files,store,tenants);
    }

    private UsageBatch batch(String owner) {
        return new UsageBatch(UUID.randomUUID().toString(),owner,LocalDate.of(2026,11,18),1,2,3,0,BigDecimal.ZERO);
    }
}
