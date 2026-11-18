package io.supportflow.customer.dto.bot;

/** 文档视图 */
public record DocVO(
        String id,
        String docNo,
        String categoryId,
        String title,
        String summary,
        int sourceType,
        String sourceText,
        int status,
        String statusText,
        int indexStatus,
        String indexStatusText,
        String indexMessage,
        int chunkCount,
        String fileName,
        long fileSize,
        String author,
        String createTime,
        String updateTime
) {
}
