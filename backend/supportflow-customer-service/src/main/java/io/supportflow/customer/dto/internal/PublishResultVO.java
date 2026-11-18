package io.supportflow.customer.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 给指定租户发一条通知的结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishResultVO(boolean delivered, String reason) {
}
