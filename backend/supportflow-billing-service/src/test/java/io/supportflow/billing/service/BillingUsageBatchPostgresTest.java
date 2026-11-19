package io.supportflow.billing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.supportflow.billing.internal.TenantPlanClient;
import io.supportflow.billing.mapper.BillingMapper;
import io.supportflow.billing.mapper.PlanMapper;
import io.supportflow.common.billing.UsageBatch;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named="SUPPORTFLOW_USAGE_TEST_BILLING_URL", matches=".+")
class BillingUsageBatchPostgresTest {
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private UsageMeterService service;
    private TransactionTemplate tx;
    private String tenant;

    @BeforeEach
    void setup() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("SUPPORTFLOW_USAGE_TEST_BILLING_URL"));
        config.setUsername("usage_test_billing");
        config.setPassword(System.getenv("SUPPORTFLOW_USAGE_TEST_BILLING_PASSWORD"));
        config.setMaximumPoolSize(10);
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
        tx.setTimeout(10);
        var factory = new MybatisSqlSessionFactoryBean();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setDataSource(pool);
        factory.setMapperLocations(new ClassPathResource("mapper/BillingMapper.xml"));
        var sql = new SqlSessionTemplate(factory.getObject());
        service = new UsageMeterService(sql.getMapper(BillingMapper.class), mock(PlanMapper.class),
                mock(TenantPlanClient.class), new SnowflakeIdGenerator(45));
        tenant = "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertEquals(false, jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
    }

    @AfterEach void close() { if (pool != null) pool.close(); }

    @Test
    void repeatedAndConcurrentDeliveryOfTheSameBatchIncrementsOnlyOnce() throws Exception {
        var batch = batch(UUID.randomUUID().toString(), tenant, LocalDate.of(2026, 11, 18), 3);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) futures.add(workers.submit(() -> { start.await(); report(batch); return null; }));
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        report(batch); // committed, but pretend the previous HTTP response was lost
        assertTotals(3, 1);
    }

    @Test
    void differentBatchesAndDatesAccumulateWhileIdsAreScopedToTheTenant() {
        String id = UUID.randomUUID().toString();
        report(batch(id, tenant, LocalDate.of(2026, 11, 18), 3));
        report(batch(UUID.randomUUID().toString(), tenant, LocalDate.of(2026, 11, 19), 5));
        report(batch(id, "OTHER_" + tenant.substring(1, 9), LocalDate.of(2026, 11, 18), 7));
        assertTotals(8, 2);
    }

    @Test
    void reusingAnIdWithDifferentContentsIsAConflictAndDoesNotChangeTotals() {
        String id = UUID.randomUUID().toString();
        report(batch(id, tenant, LocalDate.of(2026, 11, 18), 3));
        assertThrows(BizException.class, () -> report(batch(id, tenant, LocalDate.of(2026, 11, 18), 4)));
        assertThrows(BizException.class, () -> report(batch(id, tenant, LocalDate.of(2026, 11, 19), 3)));
        assertTotals(3, 1);
    }

    @Test
    void rollingBackTheAggregateAlsoRollsBackTheDeduplicationRecord() {
        var batch = batch(UUID.randomUUID().toString(), tenant, LocalDate.of(2026, 11, 18), 3);
        tx.execute(status -> { invoke(batch); status.setRollbackOnly(); return null; });
        assertTotals(0, 0);
        report(batch);
        assertTotals(3, 1);
    }

    @Test
    void deduplicationLedgerCannotBeDeletedByTheRuntimeIdentity() {
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("DELETE FROM billing_usage_batch WHERE tenant_code=?", tenant));
    }

    @Test
    void failureDuringTheAggregateWriteRollsBackTheInsertedLedgerRow() {
        var day = LocalDate.of(2026,11,18);
        report(new UsageBatch(UUID.randomUUID().toString(),tenant,day,Long.MAX_VALUE,0,0,0,BigDecimal.ZERO));
        String failedId = UUID.randomUUID().toString();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> report(
                new UsageBatch(failedId,tenant,day,1,0,0,0,BigDecimal.ZERO)));
        assertTotals(Long.MAX_VALUE,1);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM billing_usage_batch WHERE tenant_code=? AND batch_id=CAST(? AS uuid)",Integer.class,tenant,failedId));
    }

    private void report(UsageBatch batch) { tx.execute(status -> { invoke(batch); return null; }); }

    private void invoke(UsageBatch batch) {
        service.report(batch);
    }

    private void assertTotals(long expected, int batches) {
        assertEquals(expected, jdbc.queryForObject("SELECT COALESCE(sum(sessions),0) FROM billing_usage_daily WHERE tenant_code=?", Long.class, tenant));
        assertEquals(batches, jdbc.queryForObject("SELECT count(*) FROM billing_usage_batch WHERE tenant_code=?", Integer.class, tenant));
    }

    private static UsageBatch batch(String id, String tenant, LocalDate day, long sessions) {
        return new UsageBatch(id, tenant, day, sessions, sessions * 2, sessions, 2, new BigDecimal("1.25"));
    }
}
