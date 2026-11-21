package io.supportflow.customer.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.dto.session.MessageVO;
import io.supportflow.customer.event.SessionMessageEvent;
import io.supportflow.customer.mapper.*;
import io.supportflow.customer.service.bot.BotBrainService;
import io.supportflow.customer.service.metrics.BillingUsageCollector;
import io.supportflow.customer.service.session.SessionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Shared restricted PostgreSQL fixture; production services and mappers, synthetic side effects. */
abstract class SessionPostgresFixture {
    protected static final AtomicInteger IDS = new AtomicInteger(7_100_000);
    protected HikariDataSource pool;
    protected JdbcTemplate jdbc;
    protected SqlSessionTemplate sql;
    protected TransactionTemplate transaction;
    protected SessionService service;
    protected SessionMapper sessions;
    protected SessionMapper realSessions;
    protected SessionMessageMapper messages;
    protected SessionMessageMapper realMessages;
    protected BillingUsageCollector billing;
    protected BotBrainService brain;
    protected AtomicInteger committedEvents;
    protected Consumer<SessionMessageEvent> onMessage = event -> {};
    protected long sessionId;
    protected String sessionNo;

    @BeforeEach
    void prepare() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("SUPPORTFLOW_RLS_TEST_URL"));
        config.setUsername("rls_test_java");
        config.setPassword(System.getenv("SUPPORTFLOW_RLS_TEST_APP_PASSWORD"));
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(5000);
        pool = new HikariDataSource(config);
        var source = new TenantRlsConfig.TenantRlsDataSource(pool);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor());
        factory.setMapperLocations(new ClassPathResource("mapper/SessionMapper.xml"),
                new ClassPathResource("mapper/SessionMessageMapper.xml"), new ClassPathResource("mapper/SessionEventMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        realSessions = sql.getMapper(SessionMapper.class);
        sessions = mock(SessionMapper.class, delegatesTo(realSessions));
        realMessages = sql.getMapper(SessionMessageMapper.class);
        messages = mock(SessionMessageMapper.class, delegatesTo(realMessages));
        billing = mock(BillingUsageCollector.class);
        brain = mock(BotBrainService.class);
        when(brain.receptionEnabled()).thenReturn(true);
        committedEvents = new AtomicInteger();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof SessionMessageEvent persisted) {
                onMessage.accept(persisted);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { committedEvents.incrementAndGet(); }
                });
            }
        };
        service = new SessionService(sessions, messages, sql.getMapper(SessionEventMapper.class),
                mock(CustomerMapper.class), null, mock(CsatRecordMapper.class), emptyProvider(),
                mock(ChannelMapper.class), mock(ChannelKeyMapper.class), null, new SnowflakeIdGenerator(37),
                emptyProvider(), emptyProvider(), emptyProvider(), provider(brain), emptyProvider(), null,
                billing, publisher, emptyProvider());
        sessionId = IDS.incrementAndGet();
        sessionNo = "RELIABILITY_" + sessionId;
        scoped(() -> {
            jdbc.update("INSERT INTO session (id,tenant_code,session_no,channel_id,status,start_time) VALUES (?, 'TENANT_A', ?, 900001, 2, CURRENT_TIMESTAMP)", sessionId, sessionNo);
            assertEquals(false, jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class));
            return null;
        });
    }

    @AfterEach
    void cleanup() {
        try {
            if (jdbc != null) scoped(() -> {
                jdbc.update("DELETE FROM session_event WHERE session_id=?", sessionId);
                jdbc.update("DELETE FROM session_message WHERE session_id=?", sessionId);
                jdbc.update("DELETE FROM session WHERE id=?", sessionId);
                return null;
            });
        } finally {
            if (pool != null) pool.close();
        }
    }

    protected static <T> T scoped(Supplier<T> action) {
        return TenantContext.bind("TENANT_A", action::get);
    }

    @SuppressWarnings("unchecked")
    protected static <T> ObjectProvider<T> emptyProvider() { return mock(ObjectProvider.class); }

    protected static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = emptyProvider();
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
