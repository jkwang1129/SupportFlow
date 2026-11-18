package io.supportflow.customer.dto.bot;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 改状态请求体 */
public record StatusBody(
        @NotNull(message = "请选择文档状态")
        @Min(value = 1, message = "状态取值 1-4")
        @Max(value = 4, message = "状态取值 1-4")
        Integer status
) {
}
