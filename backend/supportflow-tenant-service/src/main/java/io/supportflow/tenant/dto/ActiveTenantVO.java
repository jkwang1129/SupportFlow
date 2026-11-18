package io.supportflow.tenant.dto;

import java.time.LocalDateTime;

/**
 * 活跃租户（已开通 + 有套餐）：billing 的月度出账按它逐个生成账单。
 *
 * <p>只回 status=1 且 plan_id 不为空的租户——让 billing 自己遍历订单表会漏掉
 * "试用中的租户"，也会把已注销的算进来。</p>
 */
public record ActiveTenantVO(String tenantCode, Long planId, LocalDateTime expireTime) {
}
