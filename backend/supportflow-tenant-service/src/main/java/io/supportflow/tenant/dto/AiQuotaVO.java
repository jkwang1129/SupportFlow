package io.supportflow.tenant.dto;

import java.util.List;

/**
 * 平台「租户 AI 配额」大盘。
 */
public record AiQuotaVO(AiQuotaSummaryVO summary, List<AiQuotaItemVO> items, int warnPercent) {
}
