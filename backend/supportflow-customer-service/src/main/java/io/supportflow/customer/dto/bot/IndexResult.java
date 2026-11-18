package io.supportflow.customer.dto.bot;

import java.util.List;
import java.util.Map;

/** 索引结果 */
public record IndexResult(
        String docId,
        String fileName,
        int charCount,
        int chunkCount,
        int tokenCount,
        String embeddingModel,
        String embeddingSource,
        List<Map<String, Object>> preview
) {
}
