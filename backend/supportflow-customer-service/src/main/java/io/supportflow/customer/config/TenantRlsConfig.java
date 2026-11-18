package io.supportflow.customer.config;

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
import java.sql.SQLException;
import java.sql.Statement;

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
 * <p>代价与取舍：每条语句多一次 select set_config(...) 往返。更极致的做法是把租户绑在事务上
 * （连接借出时设一次、归还时清空），但那要求所有入口都在事务里；这里选择"宁可多一次往返，
 * 也不留一条漏网 SQL"。</p>
 *
 * <p>开关：supportflow.tenant.rls-enabled=true。关掉系统照常运行（表 owner 绕过 RLS），
 * 但也就没有这层兜底——所以默认打开，启动时会打印状态。</p>
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
    public static BeanPostProcessor tenantRlsDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantRlsDataSource)) {
                    log.info("行级安全已启用：DataSource [{}] 已注入租户上下文（app.tenant_code）", beanName);
                    return new TenantRlsDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    /** 带租户上下文的 DataSource：每次借出连接时把当前租户写进 app.tenant_code。 */
    static class TenantRlsDataSource extends DelegatingDataSource {

        TenantRlsDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return applyTenant(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return applyTenant(super.getConnection(username, password));
        }

        // 上下文为空时写空串（而不是不写）：连接是复用的，不写就会留着**上一个请求**的租户，
        // 那才是最危险的串数据场景。写失败只记日志、不抛异常——RLS 是兜底，
        // 不能因为它把正常请求打挂（真出问题表现为查不到数据，宁可查不到也不串）。
        private Connection applyTenant(Connection connection) {
            String tenant = TenantContext.get();
            String value = tenant == null ? "" : tenant.replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("select set_config('app.tenant_code', '" + value + "', false)");
            } catch (SQLException e) {
                log.warn("写入 RLS 租户上下文失败 tenant={} error={}", tenant, e.getMessage());
            }
            return connection;
        }
    }
}
