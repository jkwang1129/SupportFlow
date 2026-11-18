package io.supportflow.customer.dto.session;

/** 话术入参 */
public record QuickReplyBody(String title, String content, String category,
                             Integer sortNo, Boolean isEnabled) {
}
