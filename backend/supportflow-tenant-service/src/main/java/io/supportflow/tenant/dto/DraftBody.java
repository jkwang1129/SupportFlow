package io.supportflow.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 内部请求体。
 */
public record DraftBody(
        @NotNull(message = "申请人用户 ID 不能为空")
        Long applicantId,
        @NotBlank(message = "企业名称不能为空")
        @Size(max = 128)
        String companyName,
        @Size(max = 32)
        String industry,
        @Size(max = 32)
        String scale,
        @NotBlank(message = "联系人不能为空")
        @Size(max = 64)
        String contactName,
        @Size(max = 20)
        String contactPhone,
        @Size(max = 128)
        String contactEmail
) {
}
