package io.supportflow.ops.dto;

import java.util.List;

/**
 * 随访状态表：概览 + 明细。
 */
public record FollowUpVO(FollowUpSummaryVO summary, List<FollowUpItemVO> items) {
}
