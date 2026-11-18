package io.supportflow.customer.dto.bot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建分类请求体 */
public record CategoryBody(
        @NotBlank(message = "请填写分类名称")
        @Size(max = 64)
        String name,

        Long parentId,

        Integer sortNo
) {
}
