package io.supportflow.customer.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.supportflow.common.context.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SUPPORTFLOW_RLS_TEST_URL", matches = ".+")
class TenantRlsPostgresTest {
    @Test
    void publicAvatarProjectionNeverOpensPrivateFilesOrUnderlyingMetadataTable() throws Exception {
        try (var pool = pool("rls_test_java", "APP");
             var conn = new TenantRlsConfig.TenantRlsDataSource(pool).getConnection()) {
            assertEquals(List.of("avatar.png"), strings(conn,
                    "SELECT file_name FROM public.supportflow_public_bot_avatar(900011)"));
            for (long id : List.of(900012L, 900013L, 900014L, 900015L)) {
                assertEquals(List.of(), strings(conn,
                        "SELECT file_name FROM public.supportflow_public_bot_avatar(" + id + ")"));
            }
            assertEquals(List.of(), strings(conn, "SELECT object_key FROM public.file_meta"));
        }
        try (var pool = pool("rls_test_kb", "KB"); var conn = pool.getConnection()) {
            assertEquals("42501", assertThrows(SQLException.class, () -> strings(conn,
                    "SELECT file_name FROM public.supportflow_public_bot_avatar(900011)")).getSQLState());
        }
    }

    @Test
    void schemaAdministratorAndRuntimeUseSeparateDatabasePrincipals() throws Exception {
        var migration = new io.supportflow.customer.service.tenant.CustomerMigrationConnection(
                System.getenv("SUPPORTFLOW_RLS_TEST_URL"), "supportflow",
                System.getenv("SUPPORTFLOW_RLS_TEST_MIGRATION_PASSWORD"), "rls_test_java");
        try (var conn = migration.open()) {
            assertEquals(List.of("supportflow"), strings(conn, "SELECT current_user"));
        }
        try (var runtime = pool("rls_test_java", "APP"); var conn = runtime.getConnection()) {
            assertEquals(List.of("rls_test_java"), strings(conn, "SELECT current_user"));
            assertEquals(List.of("false"), strings(conn,
                    "SELECT has_schema_privilege(current_user, 'public', 'CREATE')::text"));
        }
    }

    @Test
    void unboundChannelLookupOnlyResolvesOneValidKeyWithoutOpeningTenantTables() throws Exception {
        try (var pool = pool("rls_test_java", "APP")) {
            var source = new TenantRlsConfig.TenantRlsDataSource(pool);
            var resolver = new io.supportflow.customer.security.ChannelTenantResolver(
                    new org.springframework.jdbc.core.JdbcTemplate(source));
            assertEquals("TENANT_A", resolver.resolve("test-active-A"));
            assertEquals("TENANT_A", resolver.wechatTenant("fixture-wechat-app"));
            assertNull(resolver.wechatTenant("fixture-unconfigured-app"));
            for (String key : List.of("test-expired-B", "test-ambiguous", "test-missing")) {
                assertThrows(io.supportflow.common.exception.BizException.class, () -> resolver.resolve(key));
            }
            try (var conn = source.getConnection()) {
                assertEquals(List.of(), strings(conn, "SELECT tenant_code FROM public.channel"));
                assertEquals(List.of(), strings(conn, "SELECT tenant_code FROM public.channel_key"));
                assertEquals(List.of(), strings(conn, "SELECT token FROM public.channel_wechat_config"));
            }
        }
        try (var pool = pool("rls_test_kb", "KB"); var conn = pool.getConnection()) {
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> strings(conn, "SELECT public.supportflow_channel_tenant('test-active-A')")).getSQLState());
        }
    }

    @Test
    void tenantRoleAndTableOwnershipAreRestricted() throws Exception {
        try (var pool = pool("rls_test_java", "APP");
             var conn = new TenantRlsConfig.TenantRlsDataSource(pool).getConnection()) {
            assertEquals(List.of("rls_test_java:false:false:supportflow:true:true"), strings(conn, """
                SELECT current_user || ':' || rolsuper || ':' || rolbypassrls || ':'
                    || pg_get_userbyid(relowner) || ':' || relrowsecurity || ':' || relforcerowsecurity
                FROM pg_roles, pg_class
                WHERE rolname = current_user AND pg_class.oid = 'public.kb_chunk'::regclass
                """));
            assertEquals(List.of("false:false"), strings(conn, """
                SELECT has_schema_privilege(current_user, 'public', 'CREATE') || ':' ||
                    has_table_privilege(current_user, 'public.kb_chunk', 'TRUNCATE')
                """));
        }
    }

    @Test
    void separatesTenantReadsAndWritesWithoutApplicationPredicates() throws Exception {
        try (var pool = pool("rls_test_java", "APP")) {
            var source = new TenantRlsConfig.TenantRlsDataSource(pool);
            for (String tenant : List.of("TENANT_A", "TENANT_B")) {
                String other = tenant.equals("TENANT_A") ? "TENANT_B" : "TENANT_A";
                TenantContext.bind(tenant, () -> {
                    try (var conn = source.getConnection()) {
                        conn.setAutoCommit(false);
                        assertEquals(List.of(tenant), tenants(conn));
                        assertEquals(1, insert(conn, tenant));
                        conn.rollback();
                        assertEquals(0, update(conn, other));
                        conn.rollback();
                        assertEquals("42501", assertThrows(SQLException.class, () -> insert(conn, other)).getSQLState());
                        conn.rollback();
                    }
                    return null;
                });
            }
        }
    }

    @Test
    void unboundInvalidAndPlatformScopesCannotReadOrInsert() throws Exception {
        try (var pool = pool("rls_test_java", "APP")) {
            var source = new TenantRlsConfig.TenantRlsDataSource(pool);
            try (var conn = source.getConnection()) {
                assertDenied(conn);
            }
            for (String invalid : List.of("", "  ", "DOES_NOT_EXIST", "PLATFORM")) {
                TenantContext.bind(invalid, () -> {
                    try (var conn = source.getConnection()) {
                        assertDenied(conn);
                    }
                    return null;
                });
            }
            for (String invalid : List.of("  ", "INVALID!", "PLATFORM")) {
                TenantContext.bind(invalid, () -> {
                    try (var conn = source.getConnection()) {
                        assertEquals("42501", assertThrows(SQLException.class, () -> insert(conn, invalid)).getSQLState());
                    }
                    return null;
                });
            }
        }
    }

    @Test
    void onePhysicalConnectionIsReboundAfterRollbackAndReusedWorkerHasNoScope() throws Exception {
        try (var pool = pool("rls_test_java", "APP");
             var worker = Executors.newSingleThreadExecutor()) {
            var source = new TenantRlsConfig.TenantRlsDataSource(pool);
            var pid = worker.submit(() -> TenantContext.bind("TENANT_A", () -> {
                try (var conn = source.getConnection()) {
                    conn.setAutoCommit(false);
                    assertEquals("42501", assertThrows(SQLException.class, () -> insert(conn, "TENANT_B")).getSQLState());
                    conn.rollback();
                    assertEquals(List.of("TENANT_A"), tenants(conn));
                    return strings(conn, "SELECT pg_backend_pid()::text");
                }
            })).get();
            worker.submit(() -> {
                try (var conn = source.getConnection()) {
                    assertEquals(pid, strings(conn, "SELECT pg_backend_pid()::text"));
                    assertDenied(conn);
                }
                return null;
            }).get();
            TenantContext.bind("TENANT_B", () -> {
                try (var conn = source.getConnection()) {
                    assertEquals(pid, strings(conn, "SELECT pg_backend_pid()::text"));
                    assertEquals(List.of("TENANT_B"), tenants(conn));
                }
                return null;
            });
        }
    }

    @Test
    void failedTenantBindingEvictsThePhysicalConnection() throws Exception {
        try (var pool = pool("rls_test_java", "APP")) {
            var source = new TenantRlsConfig.TenantRlsDataSource(pool);
            var priorPid = TenantContext.bind("TENANT_A", () -> {
                try (var conn = source.getConnection()) {
                    assertEquals(List.of("TENANT_A"), tenants(conn));
                    return strings(conn, "SELECT pg_backend_pid()::text");
                }
            });
            assertThrows(SQLException.class, () -> TenantContext.bind("INVALID\0TENANT", source::getConnection));
            try (var conn = source.getConnection()) {
                assertNotEquals(priorPid, strings(conn, "SELECT pg_backend_pid()::text"));
                assertDenied(conn);
            }
        }
    }

    @Test
    void knowledgeBaseCredentialCanWriteChunksAndOnlyReadDocumentSearchMetadata() throws Exception {
        try (var pool = pool("rls_test_kb", "KB")) {
            var source = new TenantRlsConfig.TenantRlsDataSource(pool);
            TenantContext.bind("TENANT_A", () -> {
                try (var conn = source.getConnection()) {
                    assertEquals(List.of("TENANT_A"), tenants(conn));
                    assertEquals(List.of("Delivery A"), strings(conn, "SELECT title FROM public.kb_document"));
                    assertEquals("42501", assertThrows(SQLException.class,
                            () -> strings(conn, "SELECT content FROM public.kb_document")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class,
                            () -> strings(conn, "SELECT tenant_code FROM public.session")).getSQLState());
                    assertEquals("42501", assertThrows(SQLException.class,
                            () -> update(conn, "TENANT_A")).getSQLState());
                    conn.setAutoCommit(false);
                    assertEquals(1, insert(conn, "TENANT_A"));
                    try (var stmt = conn.createStatement()) {
                        assertEquals(0, stmt.executeUpdate("DELETE FROM public.kb_chunk WHERE id = 2"));
                        assertEquals(1, stmt.executeUpdate("DELETE FROM public.kb_chunk WHERE id = 100"));
                    }
                    conn.rollback();
                }
                return null;
            });
        }
    }

    private static void assertDenied(Connection conn) throws SQLException {
        assertEquals(List.of(), tenants(conn));
        assertEquals("42501", assertThrows(SQLException.class, () -> insert(conn, "TENANT_A")).getSQLState());
    }

    private static HikariDataSource pool(String username, String credential) {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("SUPPORTFLOW_RLS_TEST_URL"));
        config.setUsername(username);
        config.setPassword(System.getenv("SUPPORTFLOW_RLS_TEST_" + credential + "_PASSWORD"));
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }

    private static List<String> tenants(Connection conn) throws SQLException {
        return strings(conn, "SELECT DISTINCT tenant_code FROM public.kb_chunk ORDER BY tenant_code");
    }

    private static List<String> strings(Connection conn, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var stmt = conn.createStatement(); var rows = stmt.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }

    private static int insert(Connection conn, String tenant) throws SQLException {
        try (var stmt = conn.prepareStatement("""
                INSERT INTO public.kb_chunk (id, tenant_code, doc_id, chunk_no, content)
                VALUES (100, ?, 100, 0, 'regression')
                """)) {
            stmt.setString(1, tenant);
            return stmt.executeUpdate();
        }
    }

    private static int update(Connection conn, String tenant) throws SQLException {
        try (var stmt = conn.prepareStatement("UPDATE public.kb_chunk SET content = 'regression' WHERE tenant_code = ?")) {
            stmt.setString(1, tenant);
            return stmt.executeUpdate();
        }
    }
}
