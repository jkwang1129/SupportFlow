package io.supportflow.billing.dto;

import java.util.List;

/**
 * 批量出账结果汇总。
 *
 * @param error 拿不到活跃租户清单时的原因——前端要能区分"调用失败"和"平台里真的没有租户"
 */
public record BatchGenerateVO(String cycleKey, int tenants, int generatedCount,
                              List<String> generated, int skippedCount,
                              List<SkippedTenantVO> skipped, String error) {
}
