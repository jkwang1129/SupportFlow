package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 改状态请求体 */
public record AgentStatusUpdateBody(
        @Min(value = 1, message = "状态取值 1-在线、2-忙碌、3-小休")
        @Max(value = 3, message = "状态取值 1-在线、2-忙碌、3-小休")
        Integer status,

        @Min(value = 1, message = "最多同时接待数至少 1")
        @Max(value = 50, message = "最多同时接待数最多 50")
        Integer maxConcurrency
) {
}
