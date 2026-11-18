package io.supportflow.customer.dto.customer;

/** 导入失败明细 */
public record ImportFailure(int line, String reason) {
}
