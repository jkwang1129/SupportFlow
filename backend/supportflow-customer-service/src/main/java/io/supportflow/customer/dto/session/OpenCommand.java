package io.supportflow.customer.dto.session;

/** 访客开会话入参 */
public record OpenCommand(String appKey, String visitorToken, String visitorName, String origin) {
}
