package io.supportflow.customer.dto.tenant;

import java.util.List;

/**
 * 租户库脚本清单（平台侧展示"最新第几版、一共哪些脚本"）。
 */
public record SchemaCatalogVO(int latestVersion, List<Step> steps) {
}
