package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 访客开会话请求体。
 */
public record OpenBody(
        @NotBlank(message = "缺少渠道密钥")
        @Size(max = 64)
        String appKey,

        /** 访客身份令牌（上次打开时服务端下发的，前端本地保存） */
        @Size(max = 1024)
        String visitorToken,

        @Size(max = 64)
        String visitorName
) {
}
