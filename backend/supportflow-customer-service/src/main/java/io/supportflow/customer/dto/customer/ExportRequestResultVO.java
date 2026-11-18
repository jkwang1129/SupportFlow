package io.supportflow.customer.dto.customer;

/**
 * 企业侧「申请导出客户档案」的结果。
 */
public record ExportRequestResultVO(String id, boolean duplicated, String message) {
}
