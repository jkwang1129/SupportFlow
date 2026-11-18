package io.supportflow.customer.dto.bot;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 机器人链路体检里「实时网关那一侧」的探活结果。
 *
 * <p>只报"通不通"，不抛异常——体检接口自己挂了就没意义了。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeHealthVO(Boolean reachable, String detail, String hint) {
}
