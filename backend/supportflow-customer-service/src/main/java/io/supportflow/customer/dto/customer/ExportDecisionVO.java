package io.supportflow.customer.dto.customer;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ops 侧「这次导出放不放行」判定结果的消费方镜像。
 *
 * <p>两个服务独立部署，契约靠 HTTP 约定；这里保留"没通过审批就没有 exportId"的形状。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportDecisionVO(boolean allowed, String reason, boolean audited, String exportId) {
}
