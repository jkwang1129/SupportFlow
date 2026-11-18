package io.supportflow.tenant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tenant-service：企业、租户、企业审核（tenant_db）。
 */
@SpringBootApplication(scanBasePackages = "io.supportflow")
@MapperScan("io.supportflow.tenant.mapper")
public class TenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantApplication.class, args);
    }
}
