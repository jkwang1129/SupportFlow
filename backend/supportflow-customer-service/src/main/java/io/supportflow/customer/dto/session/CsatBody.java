package io.supportflow.customer.dto.session;

/** 满意度评价入参 */
public record CsatBody(Integer score, String feedback, String visitorToken) {
}
