package io.supportflow.realtime.dto;

/**
 * 内部推送类接口的统一回执：这条消息实际投给了几个连接。
 *
 * <p>0 是正常结果（那个会话/坐席当前没有在线连接），不是错误——调用方按"推没推出去"
 * 决定要不要落补偿，所以这里是数字而不是布尔。</p>
 */
public record DeliveredResult(int delivered) {
}
