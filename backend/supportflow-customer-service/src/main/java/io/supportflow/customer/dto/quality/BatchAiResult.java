package io.supportflow.customer.dto.quality;

import java.util.List;

/**
 * 批量 AI 质检结果。
 */
public record BatchAiResult(int processed, List<String> taskNos) {
}
