package io.supportflow.ops.internal;

import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * customer-service 内部客户端：把平台公告广播到全部租户的通知中心。
 *
 * <p>只发一条"要广播什么"，"发给谁、怎么写"都在数据面（customer-service）完成——
 * 消息表在那里，租户清单它自己去找 tenant-service 要。</p>
 *
 * <p>推送失败只记日志：公告**已经发布成功**（列表里能看到），
 * 推送是附带的触达动作，不该让发布整个回滚。</p>
 */
@Component
public class CustomerNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerNotificationClient.class);

    private final RestClient restClient;

    public CustomerNotificationClient(
            @Value("${supportflow.customer.internal-base-url:http://127.0.0.1:9093}") String baseUrl,
            @Value("${SUPPORTFLOW_CUSTOMER_OPS_SECRET:}") String secret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .requestInterceptor(CustomerInternalCredential.signer(secret, CustomerInternalCredential.Caller.OPS))
                .build();
    }

    /**
     * 广播一条公告。
     *
     * @return 实际推送到几个租户；失败返回 -1（调用方据此提示"公告已发布，但推送失败"）
     */
    @SuppressWarnings("unchecked")
    public int broadcast(String title, String content, String linkView) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/api/customer/internal/notifications/broadcast")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("title", title,
                            "content", content == null ? "" : content,
                            "linkView", linkView == null ? "" : linkView))
                    .retrieve()
                    .body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (data instanceof Map<?, ?> map && map.get("delivered") instanceof Number number) {
                return number.intValue();
            }
            return 0;
        } catch (Exception e) {
            log.warn("公告广播失败 title={} error={}（公告本身已发布，可稍后重发）", title, e.getMessage());
            return -1;
        }
    }

    /**
     * 给指定租户发一条通知（导出审批结果这类"只跟一家企业有关"的消息）。

     * @return 成功 true；失败 false（审批动作本身已经落库，通知失败只记日志）
     */
    @SuppressWarnings("unchecked")
    public boolean publish(String tenantCode, int notifyType, String title, String content, String linkView) {
        try {
            restClient.post()
                    .uri("/api/customer/internal/notifications/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("tenantCode", tenantCode,
                            "notifyType", notifyType,
                            "title", title,
                            "content", content == null ? "" : content,
                            "linkView", linkView == null ? "" : linkView))
                    .header(TenantContext.HEADER_TENANT_CODE, tenantCode)
                    .retrieve()
                    .body(Map.class);
            return true;
        } catch (Exception e) {
            log.warn("租户通知失败 tenant={} title={} error={}", tenantCode, title, e.getMessage());
            return false;
        }
    }
}
