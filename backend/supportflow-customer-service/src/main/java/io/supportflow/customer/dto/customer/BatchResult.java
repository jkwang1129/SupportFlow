package io.supportflow.customer.dto.customer;

/** 全量重算的结果 */
public record BatchResult(int scanned, int tagged, int untagged) {
}
