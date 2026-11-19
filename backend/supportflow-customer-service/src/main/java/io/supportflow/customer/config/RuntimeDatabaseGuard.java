package io.supportflow.customer.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Configuration mistakes must fail startup rather than silently bypass RLS. */
@Component
public class RuntimeDatabaseGuard implements InitializingBean {
    private final DataSource source;
    public RuntimeDatabaseGuard(DataSource source) { this.source = source; }
    @Override public void afterPropertiesSet() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("""
                SELECT r.rolsuper OR r.rolbypassrls OR r.rolcreatedb OR r.rolcreaterole
                       OR pg_has_role(current_user,c.relowner,'MEMBER')
                       OR NOT c.relrowsecurity OR NOT c.relforcerowsecurity
                FROM pg_roles r,pg_class c
                WHERE r.rolname=current_user AND c.oid='public.session'::regclass
                """)) {
            if (!result.next() || result.getBoolean(1)) {
                throw new IllegalStateException("Customer runtime requires a non-owner NOSUPERUSER NOBYPASSRLS identity and FORCE RLS");
            }
        }
    }
}
