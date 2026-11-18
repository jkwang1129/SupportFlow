package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 结束会话请求体 */
public record CloseBody(
        @NotBlank(message = "缺少租户编码")
        @Size(max = 32)
        String tenantCode,

        Long operatorId,

        @Size(max = 255, message = "结束小结最长 255 字")
        String remark
) {
}
