package io.supportflow.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 请求体。
 */
public record BackfillBody(
        @NotBlank(message = "租户编码不能为空")
        String tenantCode
) {
}
