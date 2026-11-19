package io.supportflow.customer.config;

import com.zaxxer.hikari.HikariDataSource;
import io.supportflow.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.DriverManager;
import io.supportflow.customer.security.PlatformAccess;
import org.springframework.core.env.Environment;

/**
 * 行级安全（RLS）上下文注入 —— 隔离的第二道防线。
 *
 * <p>第一道防线是应用层：所有业务 SQL 都带 tenant_code = #{tenantCode}（租户从登录态取）。
 * 但"靠每个开发者都记得写"不是安全机制，所以数据库侧还有一层兜底：
 * schema/customer_db_rls.sql 给所有带 tenant_code 的表开了 ENABLE + FORCE ROW LEVEL SECURITY，
 * 策略读的是会话变量 app.tenant_code。这里负责在**每次从连接池拿连接时**把当前租户写进去，
 * 漏写 WHERE 的 SQL 也就只能看到本租户的行。</p>
 *
 * <p>为什么包一层 DataSource 而不是用 MyBatis 拦截器：GUC 是连接级状态，必须在 SQL 执行前落到
 * **同一条连接**上；MyBatis 拦截器拿不到 Connection，DataSource 出口是唯一稳定的位置。</p>
 *
 * <p>每次借连接增加一次 set_config 往返。同一事务复用连接时，不会随内部 Scope 的变化自动重设，
 * 因此必须先确定并绑定可信租户，再进入事务。</p>
 *
 * <p>开关：supportflow.tenant.rls-enabled=true 只开启上下文注入，不证明数据库隔离已生效。
 * 还必须使用非超级用户、非 BYPASSRLS 的业务角色及 fail-closed 策略，并单独验证。</p>
 */
@Configuration
@ConditionalOnProperty(name = "supportflow.tenant.rls-enabled", havingValue = "true")
public class TenantRlsConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantRlsConfig.class);

    /**
     * 把容器里的 DataSource 包成"带租户上下文"的版本。
     * 用 BeanPostProcessor 而不是自己 new 一个：Hikari 连接池、MyBatis-Plus、事务管理器
     * 都直接依赖容器里那个 DataSource bean，换掉它（而不是并行加一个）才能保证所有 SQL 都经过这里。
     */
    @Bean
    public static BeanPostProcessor tenantRlsDataSourceWrapper(Environment env) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantRlsDataSource)) {
                    log.info("DataSource [{}] 已注入租户上下文；RLS 角色与策略需独立验证", beanName);
                    return new TenantRlsDataSource(dataSource, () -> {
                        String user = env.getProperty("SUPPORTFLOW_CUSTOMER_PLATFORM_DB_USER", "supportflow_customer_platform");
                        String password = env.getProperty("SUPPORTFLOW_CUSTOMER_PLATFORM_DB_PASSWORD", "");
                        if (password.isBlank() || user.equals(env.getProperty("spring.datasource.username"))) {
                            throw new SQLException("Separate platform database credentials are required");
                        }
                        Connection connection = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"), user, password);
                        try (var statement = connection.createStatement(); var roles = statement.executeQuery("""
                                SELECT NOT (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication)
                                  AND pg_has_role(current_user,'supportflow_platform_access','MEMBER')
                                  AND NOT pg_has_role(current_user,c.relowner,'MEMBER')
                                  AND NOT pg_has_role(current_user,'supportflow_customer_tenant','MEMBER')
                                  AND NOT pg_has_role(current_user,'supportflow_kb_tenant','MEMBER')
                                FROM pg_roles,pg_class c WHERE rolname=current_user AND c.oid='public.file_meta'::regclass
                                """)) {
                            if (!roles.next() || !roles.getBoolean(1)) throw new SQLException("Unsafe platform runtime identity");
                            return connection;
                        } catch (SQLException | RuntimeException failure) {
                            connection.close();
                            throw failure;
                        }
                    });
                }
                return bean;
            }
        };
    }

    /** 带租户上下文的 DataSource：每次借出连接时把当前租户写进 app.tenant_code。 */
    static class TenantRlsDataSource extends DelegatingDataSource {
        @FunctionalInterface interface PlatformConnection { Connection open() throws SQLException; }
        private final PlatformConnection platform;

        TenantRlsDataSource(DataSource delegate) {
            this(delegate, () -> { throw new SQLException("No platform channel configured"); });
        }
        TenantRlsDataSource(DataSource delegate, PlatformConnection platform) {
            super(delegate);
            this.platform = platform;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return applyTenant(PlatformAccess.active() ? platform.open() : super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return applyTenant(PlatformAccess.active() ? platform.open() : super.getConnection(username, password));
        }

        // 连接池可能保留上一位借用者的会话变量。每次借用都覆盖，不允许初始化失败的连接交给调用者。
        private Connection applyTenant(Connection connection) throws SQLException {
            String tenant = TenantContext.get();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_catalog.set_config('app.tenant_code', ?, false)")) {
                statement.setString(1, tenant == null ? "" : tenant);
                statement.execute();
            } catch (SQLException | RuntimeException failure) {
                discard(connection, failure);
                throw failure;
            }
            return connection;
        }

        private void discard(Connection connection, Exception failure) {
            // close() 只把连接归还 Hikari，失败时应销毁物理连接，不能让会话状态不明的连接重新入池。
            try {
                if (getTargetDataSource() instanceof HikariDataSource hikari) {
                    hikari.evictConnection(connection);
                }
            } catch (RuntimeException evictionFailure) {
                failure.addSuppressed(evictionFailure);
            } finally {
                try {
                    connection.close();
                } catch (SQLException | RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }
}
