package io.supportflow.ops.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 平台「AI 调用与成本」页的一份数据：AI 用量大盘 + 计量状态 + 是否拿得到数据。
 *
 * <p>AI 服务调不通时只有 {@code days / status / available} 有意义，其余字段不出现。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiMonitorVO(Integer days, String from, String to, AiUsageOverviewVO overview,
                          List<AiUsageDailyVO> daily, List<AiUsageModelVO> byModel,
                          List<AiUsageSceneVO> byScene, List<AiUsageTenantVO> topTenants,
                          String lastRealCallAt, AiLlmStatusVO status, boolean available) {
}
