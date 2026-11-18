package io.supportflow.ops.dto;

import java.util.List;

/**
 * 密钥列表 + 概览。
 */
public record ApiKeyListVO(ApiKeySummaryVO summary, List<ApiKeyVO> items) {
}
