package io.supportflow.customer.service.tenant;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.supportflow.common.id.SnowflakeIdGenerator;
import io.supportflow.customer.entity.SchemaUpgradeLog;
import io.supportflow.customer.mapper.SchemaUpgradeLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerMigrationConnectionTest {
    @Test void sharedRoleMigrationCannotAccidentallyRunAgainstAnIsolatedSchema() {
        var migration = mock(CustomerMigrationConnection.class);
        var logs = mock(SchemaUpgradeLogMapper.class);
        var service = new TenantSchemaUpgradeService(migration,logs,null);
        assertThrows(io.supportflow.common.exception.BizException.class,
                () -> service.upgrade("TENANT_A","tenant_a",31,"admin"));
        verifyNoInteractions(migration,logs);
    }
    @Test
    void missingOrSharedCredentialsAreRejectedBeforeConnecting() {
        assertThrows(SQLException.class, () -> new CustomerMigrationConnection(
                "jdbc:invalid", "", "", "runtime").open());
        assertThrows(SQLException.class, () -> new CustomerMigrationConnection(
                "jdbc:invalid", "runtime", "fixture-password", "runtime").open());
    }

    @Test
    void schemaDdlUsesSeparateMigrationConnectionAndLeavesTenantAuditRecord() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                SchemaUpgradeLog.class);
        var migration = mock(CustomerMigrationConnection.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var logs = mock(SchemaUpgradeLogMapper.class);
        when(migration.open()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        var service = new TenantSchemaUpgradeService(migration, logs, new SnowflakeIdGenerator(1));
        var result = service.upgrade("TENANT_A", "public", 1, "Fixture admin");
        assertEquals(1, result.toVersion());
        verify(migration).open();
        verify(statement).execute(contains("allowed_origins"));
        verify(logs).insert(any(SchemaUpgradeLog.class));
        verify(connection).close();
    }
}
