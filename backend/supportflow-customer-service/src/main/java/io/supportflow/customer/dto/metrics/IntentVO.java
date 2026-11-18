package io.supportflow.customer.dto.metrics;

/**
 * 高频问题一行：意图 + 命中次数 + 客户真实问法示例。
 *
 * <p>`sample` 是"该意图最近一次会话里客户说的第一句话"——页面上的数字要能对回真实会话，
 * 光给一个分类名（"退款与售后 42 次"）运营看不出该补哪篇知识。</p>
 */
public record IntentVO(String name, int count, String sample) {
}
