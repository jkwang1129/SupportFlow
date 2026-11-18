package io.supportflow.billing.dto;

import io.supportflow.billing.entity.BillingStatement;

import java.util.List;

/**
 * 账期账单分页。
 */
public record BillingStatementPageVO(long total, int page, int pageSize,
                                     List<BillingStatement> list) {
}
