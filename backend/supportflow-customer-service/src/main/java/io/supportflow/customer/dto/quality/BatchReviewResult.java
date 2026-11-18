package io.supportflow.customer.dto.quality;

import java.util.List;

/**
 * 批量复核结果。
 */
public record BatchReviewResult(int processed, List<String> taskNos) {
}
