package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 转接请求体 */
public record TransferBody(
        @NotBlank(message = "缺少租户编码")
        @Size(max = 32)
        String tenantCode,

        Long fromAgentId,

        @NotNull(message = "请选择要转接的客服")
        Long toAgentId,

        @Size(max = 255)
        String remark
) {
}
