package io.supportflow.customer.internal;

import io.supportflow.common.api.ApiResponse;
import io.supportflow.customer.dto.customer.UserNameRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * user-service 内部客户端：批量查询用户姓名，用于展示创建人姓名。
 */
@Component
public class UserNameClient {

    private final RestClient restClient;

    public UserNameClient(@Value("${supportflow.user.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Map<String, String> namesOf(List<String> userIds) {
        List<String> ids = userIds.stream().distinct().filter(s -> s != null && !s.isBlank()).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            ApiResponse<List<UserNameRow>> response = restClient.get()
                    .uri("/api/user/internal/users/names?ids={ids}", String.join(",", ids))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.data() == null) {
                return Map.of();
            }
            Map<String, String> names = new LinkedHashMap<>();
            for (UserNameRow item : response.data()) {
                names.put(item.id(), item.name());
            }
            return names;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
