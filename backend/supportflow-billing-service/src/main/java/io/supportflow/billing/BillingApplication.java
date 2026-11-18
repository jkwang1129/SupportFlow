package io.supportflow.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * billing-service：计费（套餐/订单/支付/发票，billing_db）。
 *
 * <p>开定时任务：每月 1 号自动给所有活跃租户出上一个自然月的账单
 * （见 {@code BillingStatementScheduler}）。</p>
 */
@SpringBootApplication(scanBasePackages = "io.supportflow")
@MapperScan("io.supportflow.billing.mapper")
@EnableScheduling
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
