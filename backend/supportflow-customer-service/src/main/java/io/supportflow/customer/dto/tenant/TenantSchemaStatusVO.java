package io.supportflow.customer.dto.tenant;

import java.util.List;

/**
 * 某个租户库的版本状态（平台侧详情页用）。
 */
public record TenantSchemaStatusVO(String tenantCode, String targetSchema, int currentVersion,
                                   int latestVersion, List<Step> pending) {
}
