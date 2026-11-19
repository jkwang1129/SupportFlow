package io.supportflow.customer.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeDatabaseGuardTest {
    @Test void startupRejectsUnsafeIdentityOrMissingProtectedTable() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        var guard = new RuntimeDatabaseGuard(source);
        assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        assertThrows(IllegalStateException.class, guard::afterPropertiesSet);
        when(result.getBoolean(1)).thenReturn(false);
        assertDoesNotThrow(guard::afterPropertiesSet);
        verify(connection,times(3)).close();
    }
}
