package io.supportflow.customer.dto.bot;

import jakarta.validation.constraints.Size;

/** 新建 / 编辑文档请求体 */
public record DocumentBody(
        @Size(max = 255)
        String title,

        Long categoryId,

        @Size(max = 200000, message = "单篇文档正文过长，请拆分后再录入")
        String content,

        @Size(max = 512)
        String summary
) {
}
