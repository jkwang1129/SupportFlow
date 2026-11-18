package io.supportflow.tenant.dto;

import java.util.List;

/**
 * 安全事件分页 + 概览 + 类型分布 + 近 7 天趋势。
 */
public record SecurityEventPageVO(int total, int page, int pageSize, List<SecurityEventVO> list,
                                  SecurityEventSummaryVO summary,
                                  List<SecurityEventTypeCountVO> typeDistribution,
                                  List<SecurityEventDailyVO> daily) {
}
