package io.supportflow.customer.dto.bot;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 知识问答请求体 */
public record AskBody(
        @NotBlank(message = "请输入要问的问题")
        @Size(max = 500)
        String question,

        @Min(value = 1, message = "最多参考 1~20 条资料")
        @Max(value = 20, message = "最多参考 1~20 条资料")
        Integer topK
) {
}
