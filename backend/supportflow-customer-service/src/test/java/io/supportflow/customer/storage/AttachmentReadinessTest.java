package io.supportflow.customer.storage;

import io.supportflow.common.readiness.SchemaReadiness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AttachmentReadinessTest {
    private final ApplicationAvailability application = mock(ApplicationAvailability.class);
    private final DataSource dataSource = mock(DataSource.class);
    private final SchemaReadiness schema = new SchemaReadiness(dataSource, Map.of("session", "id"));
    private final RustFsObjectStorage storage = mock(RustFsObjectStorage.class);
    private final AttachmentReadinessEndpoint endpoint = new AttachmentReadinessEndpoint(application, schema, storage);

    @Test
    void doesNotContactDependenciesBeforeApplicationAcceptsTraffic() {
        when(application.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertEquals(503, endpoint.readiness().getStatus());
        verifyNoInteractions(dataSource, storage);
    }

    @Test
    void skipsBucketCheckWhenSchemaIsUnavailable() throws SQLException {
        when(application.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(dataSource.getConnection()).thenThrow(new SQLException("fixture database unavailable"));
        assertEquals(503, endpoint.readiness().getStatus());
        verifyNoInteractions(storage);
    }

    @Test
    void reportsOnlyCurrentAvailabilityWithoutDependencyDetails() throws SQLException {
        when(application.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getString(1)).thenReturn("session.id");
            return rows;
        });
        when(storage.bucketAvailable()).thenReturn(true, false, true);
        assertEquals(200, endpoint.readiness().getStatus());
        var down = endpoint.readiness();
        assertEquals(503, down.getStatus());
        assertEquals(Map.of("status", "DOWN"), down.getBody());
        assertEquals(200, endpoint.readiness().getStatus());
    }

    @ParameterizedTest
    @CsvSource({"GET,5", "HEAD,5", "POST,"})
    void onlyReadFailuresSuggestRetry(String method, String retryAfter) {
        var handler = new StorageExceptionHandler();
        var response = handler.unavailable(new StorageUnavailableException(),
                new MockHttpServletRequest(method, "/api/customer/file"));
        assertEquals(503, response.getStatusCode().value());
        assertEquals(50308, response.getBody().code());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(retryAfter, response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }
}
