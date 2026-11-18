package io.supportflow.customer.dto.channel;

import java.util.List;
import java.util.Map;

/**
 * 原生渠道详情：配置回显（密钥掩码）+ 要填的字段 + 回调地址 + 模拟器提示。
 *
 * <p>{@code fields} / {@code values} / {@code webhookUrls} 的键随渠道类型变化
 * （App / 邮件 / 400 热线各一套），由渠道配置的 schema 决定，所以保持 Map。</p>
 */
public record ChannelNativeDetailVO(Integer channelType, String channelName,
                                    List<NativeField> fields,
                                    Map<String, Object> values, boolean configured,
                                    Map<String, String> webhookUrls, SimulatorHintVO simulator) {
}
