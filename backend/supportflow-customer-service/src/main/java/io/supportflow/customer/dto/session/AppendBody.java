package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 消息落库请求体 */
public record AppendBody(
        @NotBlank(message = "缺少租户编码")
        @Size(max = 32)
        String tenantCode,

        Integer senderType,
        Long senderId,
        Integer msgType,

        @Size(max = 4000, message = "单条消息最长 4000 字")
        String content,

        /** 可见范围：1-客户与坐席都可见、2-仅坐席可见（内部备注） */
        Integer visibleTo,

        /** 客户端消息号（幂等键）：同一条消息重发多少次都只落一条 */
        @Size(max = 64)
        String clientMsgNo
) {
}
