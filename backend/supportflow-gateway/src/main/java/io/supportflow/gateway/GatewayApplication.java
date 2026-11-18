package io.supportflow.gateway;

import io.supportflow.gateway.config.TenantGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SupportFlow API 网关（Spring Cloud Gateway / WebFlux）。
 *
 * <p>统一入口负责：服务路由、跨域、认租户、租户级限流与审计。
 * 租户隔离的三道防线里，网关是"第一道门"：
 * 从令牌验签取出租户（权威值）、篡改请求头直接拒绝并留痕、按租户令牌桶限流。</p>
 */
@EnableScheduling
@EnableConfigurationProperties(TenantGuardProperties.class)
@SpringBootApplication(scanBasePackages = "io.supportflow")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
