package io.supportflow.customer.dto.quality;

import java.util.List;

/**
 * 平台 AI 质量与安全大盘（近 N 天）。
 */
public record AiQualityVO(int days, String from, String to, AiQualityOverviewVO overview,
                          List<AiAlertDailyVO> alertDaily, List<AiTopRuleVO> topRules) {
}
