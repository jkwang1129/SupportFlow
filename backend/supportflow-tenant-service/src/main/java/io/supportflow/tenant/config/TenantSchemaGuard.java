package io.supportflow.tenant.config;

import io.supportflow.common.readiness.SchemaReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Startup diagnostics reuse the same read-only contract as the readiness traffic gate. */
@Component
public class TenantSchemaGuard implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TenantSchemaGuard.class);
    private final SchemaReadiness readiness;

    public TenantSchemaGuard(SchemaReadiness readiness) { this.readiness = readiness; }

    @Override public void run(ApplicationArguments args) {
        if (readiness.ready()) {
            log.info("Tenant schema readiness passed");
        } else {
            log.error("Tenant schema is not ready; business traffic is blocked. See docs/local-development.md");
        }
    }
}
