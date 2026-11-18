package io.supportflow.ops.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 随访状态表的一行（前端按租户编号合并到健康度列表）。
 *
 * <p>没排下次回访时，{@code nextVisitDate} / {@code visitRemainDays} 不出现——
 * 页面按"有没有这个字段"决定要不要显示回访倒计时。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FollowUpItemVO(String tenantCode, String csOwnerId, String csOwnerName,
                             Short renewRisk, String renewRiskText,
                             Short expandOpportunity, String expandOpportunityText,
                             Short healthScore, String lastVisitTime, String nextVisitTime,
                             String remark, String updateTime, String nextVisitDate,
                             Long visitRemainDays) {
}
