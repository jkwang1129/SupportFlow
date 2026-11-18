package io.supportflow.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

/**
 * ops-service：运营（官网配置/公告/审计/安全中心/客户成功，ops_db）。
 *
 * <p>目前落地的是**审计日志**：各服务把平台侧写操作报过来（内部接口），
 * 平台页按模块/结果/时间查，并可导出。</p>
 */
@SpringBootApplication(scanBasePackages = "io.supportflow")
@MapperScan("io.supportflow.ops.mapper")
public class OpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}
