package io.supportflow.customer.dto.bot;

/**
 * 文档详情（带回正文，供编辑框回显）。
 *
 * @param contentSource 正文来源：manual-手工录入 / chunks-由切片拼回 / null-还没有内容
 */
public record KbDetailVO(DocVO document, String content, String contentSource) {
}
