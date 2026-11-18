package io.supportflow.tenant.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Optional;

/**
 * billing-service 内部客户端：企业审核通过、租户刚开通时，给它开一个**免费版试用**。
 *
 * <p>为什么由 billing 决定"免费版是什么"：套餐的价格与配额只有 billing_db 的 plan 表知道，
 * tenant-service 不该硬编码一个套餐 ID。这里只说"给这个租户开试用"，
 * billing 自己查 FREE 套餐并把套餐 + 限流配额写回租户。</p>
 */
@Component
public class BillingTrialClient {

    private static final Logger log = LoggerFactory.getLogger(BillingTrialClient.class);

    private final RestClient restClient;

    public BillingTrialClient(@Value("${supportflow.billing.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 开一个免费版试用（按平台策略的试用天数）；失败只记日志——租户开通不能被计费服务拖住 */
    public void startFreeTrial(String tenantCode, Integer trialDays) {
        try {
            restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/billing/internal/tenants/{tenantCode}/trial")
                            .queryParamIfPresent("days", Optional.ofNullable(trialDays))
                            .build(tenantCode))
                    .retrieve()
                    .toBodilessEntity();
            log.info("已为租户开通免费版试用 tenant={} trialDays={}", tenantCode, trialDays);
        } catch (Exception e) {
            log.warn("免费版试用开通失败（租户已创建，可在平台手工开通）tenant={} error={}",
                    tenantCode, e.getMessage());
        }
    }
}
