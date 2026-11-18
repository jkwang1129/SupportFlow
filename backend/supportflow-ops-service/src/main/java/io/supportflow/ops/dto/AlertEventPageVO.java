package io.supportflow.ops.dto;

import java.util.List;

/**
 * 告警事件分页 + 概览。
 */
public record AlertEventPageVO(int total, int page, int pageSize, List<AlertEventVO> list,
                               AlertSummaryVO summary) {
}
