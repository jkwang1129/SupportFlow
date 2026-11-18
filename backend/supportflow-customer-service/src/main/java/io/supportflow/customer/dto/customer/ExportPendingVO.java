package io.supportflow.customer.dto.customer;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ops 侧「本租户可用的导出申请单」的消费方镜像。
 *
 * @param unavailable 平台安全服务调不通时为 true：与"确实没有审批单"要区分开，
 *                    否则会让人对着"没有可用的导出审批"反复提交申请
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportPendingVO(boolean found, boolean used, String exportId, String scopeJson,
                              String approveTime, Boolean unavailable, String reason) {
}
