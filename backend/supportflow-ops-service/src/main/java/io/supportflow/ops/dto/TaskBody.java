package io.supportflow.ops.dto;

/** 回访任务入参 */
public record TaskBody(String tenantCode, String companyName, Integer taskType, String title,
                       Long ownerId, String ownerName, String dueTime) {
}
