package io.supportflow.billing.dto;

import java.util.List;

/**
 * 活跃租户查询结果。
 *
 * @param codes 租户编码清单（失败时为空）
 * @param error 失败原因（成功为 null）
 */
public record ActiveTenants(List<String> codes, String error) {
}
