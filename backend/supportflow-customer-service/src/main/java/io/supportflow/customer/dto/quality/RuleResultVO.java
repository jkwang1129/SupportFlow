package io.supportflow.customer.dto.quality;

/**
 * 规则评分明细。
 */
public record RuleResultVO(String name, String type, boolean pass, String reason) {
}
