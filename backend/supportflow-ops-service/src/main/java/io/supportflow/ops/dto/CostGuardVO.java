package io.supportflow.ops.dto;

import java.math.BigDecimal;

/**
 * 成本闸门判定结果（AI 侧调用前问一次）。
 *
 * @param mode 1-正常、2-降级到规则/检索、3-暂停 AI；没配预算或读取失败都按 1 放行
 */
public record CostGuardVO(int mode, String modeText, boolean allowed, boolean degrade,
                          BigDecimal used, BigDecimal budget, BigDecimal ratio,
                          boolean configured, String reason) {
}
