package io.supportflow.billing.dto;

import java.math.BigDecimal;

/**
 * 平台账单概览里的「套餐分布」一项。
 */
public record PlanDistributionVO(String name, Long cnt, BigDecimal amount) {
}
