package io.supportflow.customer.dto.session;

/**
 * 管理端显示策略（水印开关 + 空闲登出时长）。
 *
 * <p>登录即可读，企业账号也要用；失败阈值 / IP 白名单属于安全敏感信息，不在这里。</p>
 */
public record ConsolePolicyVO(Boolean watermarkEnabled, Integer sessionTimeoutMinutes) {
}
