package io.supportflow.customer.dto.bot;

import java.util.List;

/**
 * 检索测试结果：问一句，看命中了哪些切片与相似度。
 */
public record KbSearchVO(String query, List<KbHitVO> results, boolean vectorReady) {
}
