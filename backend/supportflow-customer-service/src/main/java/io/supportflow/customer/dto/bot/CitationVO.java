package io.supportflow.customer.dto.bot;

/** 引用来源：回答里的 [n] 对应这里的第 n 条 */
public record CitationVO(
        int index,
        String chunkId,
        String docId,
        String docTitle,
        int chunkNo,
        double score,
        String content
) {
}
