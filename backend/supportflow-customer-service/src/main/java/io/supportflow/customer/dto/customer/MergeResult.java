package io.supportflow.customer.dto.customer;

/** 客户合并结果 */
public record MergeResult(String sourceCustomerNo, String targetCustomerNo,
                          int sessions, int tickets, int tags) {
}
