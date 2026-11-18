package io.supportflow.user.dto;

/** 发送「重置密码验证码」的结果（debugCode 只在非生产回显，方便联调）。 */
public record ResetCodeResult(String maskedEmail, String debugCode, int expireMinutes) {
}
