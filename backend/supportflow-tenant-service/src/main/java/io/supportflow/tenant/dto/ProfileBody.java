package io.supportflow.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 企业资料请求体。
 */
public record ProfileBody(
        @NotBlank(message = "企业名称不能为空")
        @Size(max = 128)
        String companyName,
        @NotBlank(message = "所属行业不能为空")
        @Size(max = 32)
        String industry,
        @NotBlank(message = "团队规模不能为空")
        @Size(max = 32)
        String scale,
        @NotBlank(message = "统一社会信用代码不能为空")
        @Pattern(regexp = "^[0-9A-HJ-NPQRTUWXY]{18}$",
                message = "统一社会信用代码格式不正确")
        String licenseNo,
        @NotBlank(message = "注册地址不能为空")
        @Size(max = 255)
        String registerAddress,
        @NotBlank(message = "法人代表不能为空")
        @Size(max = 64)
        String legalPerson,
        @NotNull(message = "请上传营业执照扫描件")
        Long licenseFileId,
        @NotBlank(message = "管理员姓名不能为空")
        @Size(max = 64)
        String contactName,
        @NotBlank(message = "联系电话不能为空")
        @Pattern(regexp = "^1\\d{10}$", message = "联系电话格式不正确")
        String contactPhone,
        @NotBlank(message = "企业邮箱不能为空")
        @Email(message = "企业邮箱格式不正确")
        @Size(max = 128)
        String contactEmail
) {
}
