package io.supportflow.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录第二步（双因素）入参：挑战号 + 邮箱验证码。
 *
 * <p>为什么不带账号密码：挑战号本身就是"密码已经验证通过"的凭据，
 * 再传一次密码只会多一个泄漏面。</p>
 */
public record TwoFactorLoginRequest(
        @NotBlank(message = "缺少挑战号，请重新登录")
        @Size(max = 64, message = "挑战号不合法")
        String challengeId,

        @NotBlank(message = "请输入验证码")
        @Pattern(regexp = "^\\d{6}$", message = "验证码是 6 位数字")
        String code
) {
}
