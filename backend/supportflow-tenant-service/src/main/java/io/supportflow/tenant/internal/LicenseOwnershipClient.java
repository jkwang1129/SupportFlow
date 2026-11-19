package io.supportflow.tenant.internal;

import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Component
public class LicenseOwnershipClient {
    private final RestClient client;
    public LicenseOwnershipClient(@Value("${supportflow.customer.internal-base-url}") String base,
                                  @Value("${SUPPORTFLOW_CUSTOMER_TENANT_SECRET:}") String key) {
        client = RestClient.builder().baseUrl(base).requestInterceptor(
                CustomerInternalCredential.signer(key, CustomerInternalCredential.Caller.TENANT)).build();
    }
    public void requireOwner(long applicant, Long fileId) {
        if (fileId == null) return; // draft without an attachment is still allowed
        try {
            Map<?, ?> result = client.get().uri("/api/customer/internal/license/{file}/owner/{actor}", fileId, applicant)
                    .retrieve().body(Map.class);
            if (result != null && Boolean.TRUE.equals(result.get("data"))) return;
        } catch (Exception unavailable) {
            throw new BizException(40001, "暂时无法核验营业执照，请稍后重试");
        }
        throw new BizException(40301, "营业执照不存在或不属于当前申请人");
    }
}
