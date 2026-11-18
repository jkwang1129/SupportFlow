package io.supportflow.customer.internal;

import io.supportflow.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * user-service 内部客户端：查用户的租户角色（工单权限判断用）。
 *
 * <p>为什么让 customer-service 反向查一次，而不是把角色塞进 JWT：
 * 角色是**会变的**（今天把某个客服提成主管，明天就该生效），塞进令牌要等令牌过期才生效；
 * 而且令牌是给前端用的，不该承载服务间的权限判断。这里一次内部调用就够（本地毫秒级）。</p>
 */
@Component
public class UserRoleClient {

    private static final Logger log = LoggerFactory.getLogger(UserRoleClient.class);

    private final RestClient restClient;

    public UserRoleClient(@Value("${supportflow.user.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 查用户在某租户下的角色编码。
     *
     * @return 角色编码列表；**返回 null 表示"问不到"**（用户服务不可用），
     *         返回空列表表示"这个租户还没有角色数据"（老租户，按企业管理员兼容）。
     *         两者语义不同，调用方要区别对待：问不到时保守拒绝管理类操作，
     *         但日常作业（建单/回复）照常放行。
     */
    public List<String> rolesOf(long userId, String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return List.of();
        }
        try {
            ApiResponse<List<String>> response = restClient.get()
                    .uri("/api/user/internal/users/{userId}/roles?tenantCode={tenant}",
                            userId, tenantCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (Exception e) {
            log.warn("查询用户角色失败（按保守策略处理）userId={} tenant={} error={}",
                    userId, tenantCode, e.getMessage());
            return null;
        }
    }
}
