package io.supportflow.customer.dto.bot;

/**
 * 检索命中的一个切片（与 supportflow-ai 的检索结果一一对应）。
 */
public record KbHitVO(String chunkId, String docId, String docTitle, Integer chunkNo,
                      String content, Object score, String vectorModel) {
}
