package io.supportflow.tenant.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 企业审核驳回入参。 */
public record RejectBody(
        @NotBlank(message = "请填写驳回原因")
        @Size(max = 512)
        String reason
) {
}
