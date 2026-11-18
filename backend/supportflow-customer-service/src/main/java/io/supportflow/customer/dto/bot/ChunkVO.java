package io.supportflow.customer.dto.bot;

/** 切片视图 */
public record ChunkVO(
        String id,
        int chunkNo,
        String content,
        int charCount,
        int tokenCount,
        String vectorModel,
        String createTime
) {
}
