package io.supportflow.tenant.dto;

import java.util.List;

/**
 * 客户成功列表 + 概览 + 健康模型（KPI 与列表同一份数据算出来，数字天然自洽）。
 */
public record CustomerSuccessVO(TenantHealthSummaryVO summary, List<TenantHealthVO> items,
                                HealthModelVO model) {
}
