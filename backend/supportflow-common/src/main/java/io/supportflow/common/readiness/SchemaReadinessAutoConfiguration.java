package io.supportflow.common.readiness;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Properties;

/** Ordered after JDBC creation; a scanned configuration's OnBean would run too early. */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(name = {"org.springframework.boot.health.contributor.HealthIndicator", "jakarta.servlet.Filter"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DataSource.class)
public class SchemaReadinessAutoConfiguration {
    @Bean(name = "schemaHealthIndicator")
    public SchemaReadiness schemaReadiness(DataSource source) throws IOException {
        Properties properties = new Properties();
        try (var input = new ClassPathResource("schema/readiness.properties").getInputStream()) {
            properties.load(input);
        }
        var tables = new HashMap<String, String>();
        properties.forEach((key, value) -> tables.put(key.toString(), value.toString()));
        return new SchemaReadiness(source, tables);
    }

    @Bean
    public FilterRegistrationBean<SchemaReadinessFilter> schemaReadinessGate(SchemaReadiness readiness) {
        var registration = new FilterRegistrationBean<>(new SchemaReadinessFilter(readiness));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
