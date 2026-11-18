package io.supportflow.ops.dto;

import java.util.List;

/**
 * 成本预算列表 + 超限动作下拉选项。
 */
public record BudgetListVO(List<BudgetVO> items, List<OptionVO> actions) {
}
