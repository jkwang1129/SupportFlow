package io.supportflow.common.readiness;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SchemaReadinessTest {
    private SchemaReadiness schema(String... available) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        var index = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(result.next()).thenAnswer(call -> index.incrementAndGet() < available.length);
        when(result.getString(1)).thenAnswer(call -> available[index.get()]);
        return new SchemaReadiness(source, Map.of("ticket", "id,tenant_code"));
    }

    @Test void requiresEveryRegisteredColumn() throws Exception {
        assertTrue(schema("ticket.id", "ticket.tenant_code", "other.id").ready());
        assertFalse(schema("ticket.id").ready());
        assertFalse(schema().ready());
    }

    @Test void databaseFailureDoesNotExposeCredentialsInHealth() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("jdbc:secret-password@private"));
        var readiness = new SchemaReadiness(source, Map.of("ticket", "id"));
        assertEquals("DOWN", readiness.health().getStatus().getCode());
        assertTrue(readiness.health().getDetails().isEmpty());
    }

    @Test void metadataIsReadOnlyPrivilegeAwareAndTimeBounded() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        assertFalse(new SchemaReadiness(source, Map.of("ticket", "id")).ready());
        verify(statement).setQueryTimeout(2);
        verify(statement).executeQuery(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("has_column_privilege") && sql.startsWith("SELECT") && !sql.contains("FROM public.ticket")));
        verify(connection).close();
    }

    @Test void unavailableBusinessRequestNeverReachesHandler() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/tickets");
        var response = new MockHttpServletResponse();
        AtomicBoolean entered = new AtomicBoolean();
        new SchemaReadinessFilter(schema()).doFilter(request, response, (req, res) -> entered.set(true));
        assertFalse(entered.get());
        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertFalse(response.getContentAsString().contains("ticket"));
    }

    @Test void probesAreReachableAndReadyBusinessRequestContinues() throws Exception {
        for (String path : new String[]{"/actuator/health/liveness", "/actuator/health/readiness"}) {
            AtomicBoolean entered = new AtomicBoolean();
            new SchemaReadinessFilter(schema()).doFilter(new MockHttpServletRequest("GET", path),
                    new MockHttpServletResponse(), (req, res) -> entered.set(true));
            assertTrue(entered.get());
        }
        AtomicBoolean entered = new AtomicBoolean();
        new SchemaReadinessFilter(schema("ticket.id", "ticket.tenant_code")).doFilter(
                new MockHttpServletRequest("GET", "/api/tickets"), new MockHttpServletResponse(),
                (req, res) -> entered.set(true));
        assertTrue(entered.get());
    }

    @Test void rejectsEmptyOrMalformedContract() {
        assertThrows(IllegalArgumentException.class, () -> new SchemaReadiness(mock(DataSource.class), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SchemaReadiness(mock(DataSource.class), Map.of("a", "")));
    }

    @Test void autoConfigurationOnlyRegistersForDatabaseServices() {
        var runner = new WebApplicationContextRunner().withConfiguration(
                AutoConfigurations.of(SchemaReadinessAutoConfiguration.class));
        runner.run(context -> assertFalse(context.containsBean("schemaHealthIndicator")));
        runner.withBean(DataSource.class, () -> mock(DataSource.class)).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBean("schemaHealthIndicator"));
            assertTrue(context.containsBean("schemaReadinessGate"));
        });
    }
}
