package io.supportflow.ops.dto;

/**
 * 登录相关策略（user-service 登录链路与网关 IP 白名单读它）。
 *
 * <p>双因素也在这里：登录链路一次拉齐"锁定 / 白名单 / 双因素"，少一条会忘的链路。</p>
 */
public record LoginPolicyVO(Integer failThreshold, Integer lockMinutes,
                            Boolean ipWhitelistEnabled, String ipWhitelist,
                            Boolean twoFactorRequired, Integer twoFactorScope) {
}
