package io.supportflow.ops.dto;

/**
 * 平台安全策略（没配过时返回一套默认值，不写库）。
 */
public record SecurityPolicyVO(Integer loginFailThreshold, Integer loginLockMinutes,
                               Boolean ipWhitelistEnabled, String ipWhitelist,
                               Boolean exportNeedApproval, Boolean watermarkEnabled,
                               Integer sessionTimeoutMinutes, Boolean twoFactorRequired,
                               Integer twoFactorScope, Boolean configured,
                               String updateTime, String editor) {
}
