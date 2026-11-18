package io.supportflow.customer.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 平台公告广播结果。
 *
 * <p>标题为空时只回 {@code delivered=0 + reason}；正常广播回
 * {@code delivered + tenants}，所以用 NON_NULL 让不属于该分支的键不出现。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BroadcastResultVO(int delivered, Integer tenants, String reason) {
}
