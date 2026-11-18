package io.supportflow.customer.dto.tenant;

import java.util.List;

/** 升级结果（控制面拿它写升级历史 + 更新 tenant.db_version） */
public record UpgradeResult(int fromVersion, int toVersion, List<String> appliedScripts, String message) {
}
