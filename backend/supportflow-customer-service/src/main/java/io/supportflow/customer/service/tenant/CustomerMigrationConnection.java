package io.supportflow.customer.service.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Explicit migration credentials. The normal application pool never executes DDL. */
@Component
public class CustomerMigrationConnection {
    private final String url;
    private final String user;
    private final String password;
    private final String applicationUser;

    public CustomerMigrationConnection(@Value("${spring.datasource.url}") String url,
            @Value("${SUPPORTFLOW_CUSTOMER_MIGRATION_USER:}") String user,
            @Value("${SUPPORTFLOW_CUSTOMER_MIGRATION_PASSWORD:}") String password,
            @Value("${spring.datasource.username}") String applicationUser) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.applicationUser = applicationUser;
    }

    public Connection open() throws SQLException {
        if (user.isBlank() || password.isBlank() || user.equals(applicationUser)) {
            throw new SQLException("Configure separate customer migration credentials before upgrading schemas");
        }
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "120");
        return DriverManager.getConnection(url, properties);
    }
}
