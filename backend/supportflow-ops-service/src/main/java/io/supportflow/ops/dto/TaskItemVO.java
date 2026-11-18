package io.supportflow.ops.dto;

/**
 * 回访任务的一行。
 *
 * <p>与随访行不同，这里 {@code dueRemainDays} 即使没有到期时间也会**显式给 null**：
 * 页面用它判断"要不要画倒计时"，字段缺失和 null 在这里被一视同仁，保持原样最稳。</p>
 */
public record TaskItemVO(String id, String tenantCode, String companyName,
                         Short taskType, String taskTypeText, String title, String ownerName,
                         String dueTime, Short status, String statusText, String result,
                         String finishTime, String createTime, Long dueRemainDays,
                         boolean overdue) {
}
