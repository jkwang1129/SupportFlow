package io.supportflow.customer.dto.quality;

/** 质检规则：名称 + 检查内容（交给 AI 判定） */
public record RuleSpec(String name, String content) {
}
