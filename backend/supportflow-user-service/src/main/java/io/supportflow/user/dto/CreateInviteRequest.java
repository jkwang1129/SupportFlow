package io.supportflow.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建邀请请求体。
 */
public record CreateInviteRequest(
        @Size(max = 20, message = "手机号最长 20 个字符")
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "请填写受邀邮箱")
        @Size(max = 128, message = "邮箱最长 128 个字符")
        @Email(message = "邮箱格式不正确")
        String email,

        @NotBlank(message = "请选择角色")
        String roleCode,

        @Min(value = 1, message = "有效期不能少于 1 天")
        @Max(value = 30, message = "有效期不能超过 30 天")
        Integer expireDays
) {
}
