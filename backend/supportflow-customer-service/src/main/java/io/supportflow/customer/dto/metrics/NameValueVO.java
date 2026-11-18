package io.supportflow.customer.dto.metrics;

/** 「名称 + 数量」的分布项：渠道 / 意图 / 情绪分布共用。 */
public record NameValueVO(String name, int count) {
}
