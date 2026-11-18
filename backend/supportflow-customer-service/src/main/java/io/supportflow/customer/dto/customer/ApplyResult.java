package io.supportflow.customer.dto.customer;

/** 单个客户重算的结果 */
public record ApplyResult(int ruleCount, int tagged, int untagged) {
}
