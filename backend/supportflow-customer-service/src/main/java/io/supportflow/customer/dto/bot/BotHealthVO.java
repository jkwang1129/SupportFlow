package io.supportflow.customer.dto.bot;

/**
 * 机器人链路体检：两个总开关 + 两侧探活 + 一句话结论。
 *
 * <p>两个开关是**配置**不是故障：关掉了机器人自然不回话，页面要能一眼区分。</p>
 */
public record BotHealthVO(String tenantCode, boolean botEnabled, boolean receptionEnabled,
                          BotAiHealthVO ai, RealtimeHealthVO realtime, String hint) {
}
