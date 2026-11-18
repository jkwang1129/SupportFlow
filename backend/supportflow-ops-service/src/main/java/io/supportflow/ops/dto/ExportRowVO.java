package io.supportflow.ops.dto;

/**
 * 数据导出申请单（平台审批台的一行）。
 */
public record ExportRowVO(String id, String tenantCode, Integer exportType, String exportTypeText,
                          String scopeJson, Integer status, String statusText,
                          String requesterName, String approverName, String rejectReason,
                          String requestTime, String approveTime, Integer exportRows,
                          String exportTime) {
}
