package io.supportflow.customer.dto.bot;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 检索请求体 */
public record SearchBody(
        @NotBlank(message = "请输入要检索的内容")
        @Size(max = 500)
        String query,

        @Min(value = 1, message = "最多返回 1~20 条")
        @Max(value = 20, message = "最多返回 1~20 条")
        Integer topK
) {
}
