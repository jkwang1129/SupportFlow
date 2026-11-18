package io.supportflow.user.dto;

/** 校验通过后的身份（用于签发 token 与写登录日志） */
public record Identity(long userId, String account, String tenantCode) {
}
