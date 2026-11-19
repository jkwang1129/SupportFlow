package io.supportflow.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.supportflow.common.billing.UsageBatch;
import io.supportflow.common.auth.UsageBatchCredential;
import java.time.Clock;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.util.Map;

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
    private final String secret;

    public BillingUsageClient(@Value("${supportflow.billing.internal-base-url}") String baseUrl,
                              @Value("${SUPPORTFLOW_BILLING_CUSTOMER_SECRET:}") String secret) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.secret = secret;
    }

    /** A business receipt must identify this exact batch; HTTP 200 alone is not acceptance. */
    public boolean report(UsageBatch batch) {
        try {
            Map<?, ?> response = restClient.post().uri(UsageBatchCredential.PATH)
                    .header(UsageBatchCredential.HEADER, UsageBatchCredential.sign(secret, batch, Clock.systemUTC()))
                    .contentType(MediaType.APPLICATION_JSON).body(batch).retrieve().body(Map.class);
            if (response == null || !(response.get("code") instanceof Number code) || code.intValue() != 0)
                return false;
            return response.get("data") instanceof Map<?, ?> receipt && Boolean.TRUE.equals(receipt.get("accepted"))
                    && batch.batchId().equals(receipt.get("batchId"));
        } catch (Exception e) {
            log.warn("用量上报失败 tenant={} batch={} error={}", batch.tenantCode(), batch.batchId(), e.getClass().getSimpleName());
            return false;
        }
    }
}
