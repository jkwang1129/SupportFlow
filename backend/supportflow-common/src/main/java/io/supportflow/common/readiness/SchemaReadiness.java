package io.supportflow.common.readiness;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A read-only minimum schema contract, not a migration runner or full drift verifier. */
public final class SchemaReadiness implements HealthIndicator {
    private final DataSource source;
    private final Set<String> required;

    public SchemaReadiness(DataSource source, Map<String, String> tables) {
        this.source = source;
        Set<String> columns = new HashSet<>();
        tables.forEach((table, names) -> {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Invalid readiness table");
            for (String name : names.split(",", -1)) {
                String column = name.trim();
                if (!column.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Invalid readiness column");
                columns.add(table + "." + column);
            }
        });
        if (columns.isEmpty()) throw new IllegalArgumentException("A schema readiness contract is required");
        required = Set.copyOf(columns);
    }

    public boolean ready() {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            // Do not read business rows or rely on information_schema visibility: INSERT-only
            // privileges can make a column visible even after SELECT has been revoked.
            try (var result = statement.executeQuery("""
                    SELECT c.relname || '.' || a.attname
                    FROM pg_catalog.pg_attribute a
                    JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public' AND c.relkind IN ('r','p')
                      AND a.attnum>0 AND NOT a.attisdropped
                      AND has_schema_privilege(n.oid,'USAGE')
                      AND has_column_privilege(c.oid,a.attnum,'SELECT')
                    """)) {
                Set<String> available = new HashSet<>();
                while (result.next()) available.add(result.getString(1));
                return available.containsAll(required);
            }
        } catch (SQLException unavailable) {
            // SQL/DSN/credentials and object names must not enter public probe details.
            return false;
        }
    }

    @Override public Health health() { return (ready() ? Health.up() : Health.down()).build(); }
}
