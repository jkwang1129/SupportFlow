package io.supportflow.customer.dto.session;

import java.math.BigDecimal;

/** 满意度评价结果 */
public record CsatResult(int score, String feedback, BigDecimal average) {
}
