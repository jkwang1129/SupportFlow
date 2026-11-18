package io.supportflow.tenant.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * user-service 内部客户端：企业审核通过后回填 sys_user.tenant_code。
 */
@Component
public class UserTenantBackfillClient {

    private static final Logger log = LoggerFactory.getLogger(UserTenantBackfillClient.class);

    private final RestClient restClient;

    public UserTenantBackfillClient(@Value("${supportflow.user.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public void backfillTenantCode(long userId, String tenantCode) {
        try {
            restClient.put()
                    .uri("/api/user/internal/users/{userId}/tenant-code", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new BackfillBody(tenantCode))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("用户租户编码回填失败 userId={}, tenantCode={}", userId, tenantCode, e);
        }
    }

    public record BackfillBody(String tenantCode) {
    }
}
