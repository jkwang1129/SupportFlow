package io.supportflow.ops.dto;

/**
 * 随访概览：已跟进 / 即将到期 / 已逾期 / 高风险已跟进 / 有商机已跟进。
 */
public record FollowUpSummaryVO(int followed, int visitDueSoon, int visitOverdue,
                                int highRiskFollowed, int expandFollowed) {
}
