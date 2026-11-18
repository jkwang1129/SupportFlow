package io.supportflow.user.dto;

/**
 * 登录结果。
 *
 * <p>两个形状：</p>
 * <ol>
 *   <li><b>直接登录成功</b>：`token` 有值，其余二次验证字段为 null；</li>
 *   <li><b>需要二次验证</b>（安全中心开了「强制双因素认证」且账号在覆盖范围内）：
 *       `token` 为 null，`needTwoFactor=true`，带上 `challengeId` 与掩码邮箱——
 *       前端拿挑战号去第二步换 token。**这一步绝不发 token**，否则双因素形同虚设。</li>
 * </ol>
 */
public record LoginResponse(
        String token,
        long userId,
        String userNo,
        String name,
        int userType,
        /** 租户编码：企业账号审核开通前为平台租户 PLATFORM，开通后为正式 T 编码 */
        String tenantCode,
        String avatar,
        /** 是否还需要二次验证（true 时 token 为 null） */
        Boolean needTwoFactor,
        /** 二次验证的挑战号（密码已通过这件事的凭据，5 分钟内有效） */
        String challengeId,
        /** 验证码发到哪个邮箱（掩码后的，页面上告诉用户去哪收） */
        String maskedEmail,
        /** 验证码有效期（秒），前端倒计时用 */
        Integer expireSeconds,
        /** 本地联调：未配 SMTP 时把验证码带回来（two-factor-code-debug=true 才有值） */
        String debugCode
) {

    /** 二次验证挑战（不发 token） */
    public static LoginResponse challenge(long userId, String userNo, String name, int userType,
                                          String tenantCode, String challengeId, String maskedEmail,
                                          int expireSeconds, String debugCode) {
        return new LoginResponse(null, userId, userNo, name, userType, tenantCode, null,
                true, challengeId, maskedEmail, expireSeconds, debugCode);
    }
}
