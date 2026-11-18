package io.supportflow.ops.dto;

/** 安全策略入参 */
public record PolicyBody(Integer loginFailThreshold, Integer loginLockMinutes,
                         Boolean ipWhitelistEnabled, String ipWhitelist,
                         Boolean exportNeedApproval, Boolean watermarkEnabled,
                         Integer sessionTimeoutMinutes, Boolean twoFactorRequired,
                         Integer twoFactorScope) {
}
