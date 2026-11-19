package io.supportflow.customer.config;

import io.supportflow.common.context.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TenantRlsDataSourceTest {

    @Test
    void failedContextBindingClosesConnectionAndPreservesTheOriginalError() {
        FakeConnection fake = new FakeConnection();
        fake.bindFailure = new SQLException("database rejected context");
        fake.closeFailure = new SQLException("connection could not be closed");
        var source = dataSource(fake);

        SQLException error = assertThrows(SQLException.class,
                () -> TenantContext.bind("TENANT_A", source::getConnection));

        assertSame(fake.bindFailure, error);
        assertTrue(fake.closed);
        assertArrayEquals(new Throwable[]{fake.closeFailure}, error.getSuppressed());
    }

    @Test
    void credentialOverloadAlsoClosesConnectionWithoutReturningItOnFailure() {
        FakeConnection fake = new FakeConnection();
        fake.bindFailure = new SQLException("connection lost");
        var source = dataSource(fake);

        assertSame(fake.bindFailure, assertThrows(SQLException.class, () ->
                TenantContext.bind("TENANT_A", () -> source.getConnection("app", "unused"))));
        assertTrue(fake.closed);
    }

    @Test
    void rebindsReusedConnectionAndClearsPreviousTenantWhenWorkerHasNoScope() throws Exception {
        FakeConnection fake = new FakeConnection();
        var source = dataSource(fake);
        // Use one worker and one simulated physical connection for all three requests.
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertSame(fake.connection, worker.submit(() ->
                    TenantContext.bind("TENANT_A", source::getConnection)).get());
            assertEquals("TENANT_A", fake.tenant);
            assertSame(fake.connection, worker.submit(() -> source.getConnection()).get());
            assertEquals("", fake.tenant);
            assertSame(fake.connection, worker.submit(() ->
                    TenantContext.bind("TENANT_B", source::getConnection)).get());
            assertEquals("TENANT_B", fake.tenant);
        }
    }

    @Test
    void tenantIsBoundAsDataWithoutNormalizingItIntoTheLegacyEmptyContext() throws Exception {
        FakeConnection fake = new FakeConnection();
        var source = dataSource(fake);

        assertSame(fake.connection, TenantContext.bind("TEAM_O'BRIEN", source::getConnection));
        assertEquals("TEAM_O'BRIEN", fake.tenant);
        assertSame(fake.connection, TenantContext.bind("  ", source::getConnection));
        assertEquals("  ", fake.tenant);
    }

    private static TenantRlsConfig.TenantRlsDataSource dataSource(FakeConnection fake) {
        return new TenantRlsConfig.TenantRlsDataSource(new AbstractDataSource() {
            @Override
            public Connection getConnection() {
                return fake.connection;
            }

            @Override
            public Connection getConnection(String username, String password) {
                return fake.connection;
            }
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class FakeConnection {
        private String tenant;
        private SQLException bindFailure;
        private SQLException closeFailure;
        private boolean closed;

        private final Connection connection = proxy(Connection.class, (conn, method, args) ->
                switch (method.getName()) {
                    case "prepareStatement" -> statement();
                    case "close" -> {
                        closed = true;
                        if (closeFailure != null) {
                            throw closeFailure;
                        }
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });

        private PreparedStatement statement() {
            String[] parameter = new String[1];
            return proxy(PreparedStatement.class, (stmt, method, args) ->
                    switch (method.getName()) {
                        case "setString" -> {
                            assertEquals(1, args[0]);
                            parameter[0] = (String) args[1];
                            yield null;
                        }
                        case "execute" -> {
                            if (bindFailure != null) {
                                throw bindFailure;
                            }
                            tenant = parameter[0];
                            yield true;
                        }
                        case "close" -> null;
                        default -> throw new AssertionError("Unexpected statement call: " + method.getName());
                    });
        }
    }
}
