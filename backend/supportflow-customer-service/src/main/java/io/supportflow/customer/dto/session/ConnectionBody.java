package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 上下线请求体 */
public record ConnectionBody(
        @NotBlank(message = "缺少租户编码")
        @Size(max = 32)
        String tenantCode,

        @NotNull(message = "缺少坐席 ID")
        Long agentId,

        @NotNull(message = "缺少连接状态")
        Boolean connected
) {
}
