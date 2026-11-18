package io.supportflow.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册验证码请求体。
 */
public record SendRegisterCodeRequest(
        @NotBlank(message = "请先填写邮箱")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128)
        String email,

        @NotBlank(message = "请先填写图形验证码")
        String captchaId,

        @NotBlank(message = "请先填写图形验证码")
        @Size(max = 8)
        String captchaCode
) {
}
