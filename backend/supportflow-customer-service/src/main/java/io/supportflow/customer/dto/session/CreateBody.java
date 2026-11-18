package io.supportflow.customer.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建技能组请求体 */
public record CreateBody(
        @NotBlank(message = "请填写技能组名称")
        @Size(max = 64)
        String name,

        @Size(max = 255)
        String description,

        Integer overflowAfterSeconds
) {
}
