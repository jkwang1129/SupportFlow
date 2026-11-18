package io.supportflow.ops.dto;

/**
 * 回访任务概览：待回访 / 逾期 / 今日到期 / 已完成。
 */
public record TaskSummaryVO(int pending, int overdue, int dueToday, int finished) {
}
