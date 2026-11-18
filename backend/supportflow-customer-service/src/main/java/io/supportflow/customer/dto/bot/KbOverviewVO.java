package io.supportflow.customer.dto.bot;

/**
 * 知识库总览：文档数 / 已发布 / 草稿 / 已索引 / 切片总数 / 向量库是否就绪。
 */
public record KbOverviewVO(Long docTotal, Long published, Long draft, Long indexed,
                           Long chunkTotal, boolean vectorReady, int categoryCount) {
}
