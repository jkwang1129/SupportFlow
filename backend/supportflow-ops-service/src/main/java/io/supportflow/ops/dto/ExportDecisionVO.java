package io.supportflow.ops.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 「这次导出放不放行」的判定结果（导出接口每次导前问一次 / 下载前按单号消费）。
 *
 * <p>没通过审批时不会有 {@code exportId}，所以 null 的字段直接不出现在报文里——
 * 这个接口的调用方（customer-service）是按键判断的。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportDecisionVO(boolean allowed, String reason, boolean audited, String exportId) {
}
