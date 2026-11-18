package io.supportflow.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 接受邀请请求体。
 */
public record AcceptInviteRequest(
        @NotBlank(message = "请填写姓名")
        @Size(max = 64, message = "姓名最长 64 个字符")
        String name,

        @NotBlank(message = "请设置登录密码")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "密码需不少于 8 位，且同时包含字母和数字"
        )
        String password,

        @Size(max = 20, message = "手机号最长 20 个字符")
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Size(max = 128, message = "邮箱最长 128 个字符")
        @Email(message = "邮箱格式不正确")
        String email
) {
}
