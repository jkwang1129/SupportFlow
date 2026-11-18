package io.supportflow.customer.dto.wechat;

import io.supportflow.customer.entity.SubscribeMessageLog;

import java.util.List;

/**
 * 发一条测试订阅消息的结果 + 最近几条发送记录。
 *
 * <p>小程序订阅消息"发不出去"极其常见（没授权 / 次数用完 / 字段不符），
 * 所以测试按钮必须把失败原因和最近的日志一起给出来。</p>
 */
public record SubscribeTestVO(boolean sent, String message, List<SubscribeMessageLog> logs) {
}
