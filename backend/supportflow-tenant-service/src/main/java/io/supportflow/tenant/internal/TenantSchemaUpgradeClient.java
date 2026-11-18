package io.supportflow.tenant.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * customer-service 内部客户端：租户库升级在数据面执行（脚本副本、SchemaGuard 都在那边），
 * tenant-service 只负责下发与留痕。
 */
@Component
public class TenantSchemaUpgradeClient {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaUpgradeClient.class);

    private final RestClient restClient;

    public TenantSchemaUpgradeClient(@Value("${supportflow.customer.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 脚本清单（最新版本号 + 每一步）：平台概览要看"有多少租户的库版本落后"。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> catalog() {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/customer/internal/tenant-schema/catalog")
                    .retrieve()
                    .body(Map.class);
            return body == null ? null : (Map<String, Object>) body.get("data");
        } catch (Exception e) {
            log.warn("查询租户库脚本清单失败 error={}", e.getMessage());
            return null;
        }
    }

    /** 版本状态（当前版本 / 最新版本 / 待执行脚本）。数据面不可用时返回 null，调用方降级处理。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> status(String tenantCode, String targetSchema) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(uri -> uri.path("/api/customer/internal/tenant-schema/status")
                            .queryParam("tenantCode", tenantCode)
                            .queryParam("targetSchema", targetSchema == null ? "public" : targetSchema)
                            .build())
                    .retrieve()
                    .body(Map.class);
            return body == null ? null : (Map<String, Object>) body.get("data");
        } catch (Exception e) {
            log.warn("查询租户库版本失败 tenant={} schema={} error={}", tenantCode, targetSchema, e.getMessage());
            return null;
        }
    }

    /** 执行升级。失败会抛出（业务异常由调用方转成升级历史里的一条失败记录）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> upgrade(String tenantCode, String targetSchema, Integer toVersion) {
        Map<String, Object> body = restClient.post()
                .uri(uri -> {
                    uri.path("/api/customer/internal/tenant-schema/upgrade")
                            .queryParam("tenantCode", tenantCode)
                            .queryParam("targetSchema", targetSchema == null ? "public" : targetSchema);
                    if (toVersion != null) {
                        uri.queryParam("toVersion", toVersion);
                    }
                    return uri.build();
                })
                .retrieve()
                .body(Map.class);
        return body == null ? null : (Map<String, Object>) body.get("data");
    }
}
