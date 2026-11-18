package io.supportflow.ops.dto;

/** 随访安排入参 */
public record FollowUpBody(String tenantCode, Integer healthScore, Integer renewRisk,
                           Integer expandOpportunity, Long csOwnerId, String csOwnerName,
                           String nextVisitTime, String remark) {
}
