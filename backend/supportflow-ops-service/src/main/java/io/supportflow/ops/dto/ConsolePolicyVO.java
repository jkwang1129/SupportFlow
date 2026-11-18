package io.supportflow.ops.dto;

/**
 * 管理端要读的那两个策略字段（水印、空闲登出）。
 *
 * <p>不复用安全策略接口：失败阈值 / IP 白名单属于安全敏感信息，不给前端读。</p>
 */
public record ConsolePolicyVO(Boolean watermarkEnabled, Integer sessionTimeoutMinutes) {
}
