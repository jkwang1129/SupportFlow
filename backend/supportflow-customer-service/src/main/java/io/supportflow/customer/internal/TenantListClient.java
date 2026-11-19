package io.supportflow.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * tenant-service 内部客户端：拿全部租户编码。
 *
 * <p>用在哪：平台公告要推送给**所有**租户的通知中心，而租户清单的权威来源在 tenant_db，
 * customer-service 不该自己去猜（比如按"有会话数据的租户"来推，就会漏掉新开通还没产生会话的企业）。</p>
 *
 * <p>拿不到就返回空列表，当前批次的工作跳过，不执行无租户的数据操作。</p>
 */
@Component
public class TenantListClient {

    private static final Logger log = LoggerFactory.getLogger(TenantListClient.class);

    private final RestClient restClient;

    public TenantListClient(@Value("${supportflow.tenant.internal-base-url:http://127.0.0.1:9092}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 全部租户编码（状态正常、未删除） */
    @SuppressWarnings("unchecked")
    public List<String> allTenantCodes() {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/tenant/internal/tenants/codes")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            Object data = body == null ? null : body.get("data");
            if (!(data instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(code -> code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}"))
                    .filter(code -> !"PLATFORM".equalsIgnoreCase(code)).distinct().toList();
        } catch (Exception e) {
            log.warn("查询租户清单失败，本轮跳过 error={}", e.getMessage());
            return List.of();
        }
    }
}
