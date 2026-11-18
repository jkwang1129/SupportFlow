package io.supportflow.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * billing-service 内部客户端：把业务用量（会话 / 消息 / AI 调用 / 席位 / 存储）报给计费。
 *
 * <p>为什么由 customer-service 上报而不是网关：只有业务侧才知道「开了几个会话、发了几条消息、
 * 调了几次 AI」；网关只看得见请求数。两边各报各的，口径互不干扰。</p>
 *
 * <p>上报失败只记日志、不抛异常：计量是旁路，不能因为它把会话链路拖挂。</p>
 */
@Component
public class BillingUsageClient {

    private static final Logger log = LoggerFactory.getLogger(BillingUsageClient.class);

    private final RestClient restClient;

    public BillingUsageClient(@Value("${supportflow.billing.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 上报一条当天用量（billing 侧按 (租户, 日期) 幂等累加） */
    public boolean report(String tenantCode, LocalDate statDate, long sessions, long messages,
                          long aiCalls, long seats, BigDecimal storageGb) {
        try {
            restClient.post()
                    .uri("/api/billing/internal/usage/report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new UsageBody(tenantCode, statDate, sessions, messages, aiCalls, seats, storageGb))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("用量上报失败 tenant={} sessions={} messages={} aiCalls={} error={}",
                    tenantCode, sessions, messages, aiCalls, e.getMessage());
            return false;
        }
    }

    /** 与 billing 侧 UsageReport 一一对应 */
    record UsageBody(String tenantCode, LocalDate statDate, long sessions, long messages,
                     long aiCalls, long seats, BigDecimal storageGb) {
    }
}
