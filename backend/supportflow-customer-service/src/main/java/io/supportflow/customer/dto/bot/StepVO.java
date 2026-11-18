package io.supportflow.customer.dto.bot;

/** 编排轨迹：每一步做了什么，前端展示"这次是怎么查的" */
public record StepVO(String node, String detail) {
}
