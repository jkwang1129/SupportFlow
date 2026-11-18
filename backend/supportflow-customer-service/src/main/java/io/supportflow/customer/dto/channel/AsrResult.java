package io.supportflow.customer.dto.channel;

/** 识别结果：没配置时 available=false（不是错误），调用方据此降级 */
public record AsrResult(boolean available, String text, String hint) {
}
