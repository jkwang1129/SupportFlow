package io.supportflow.customer.dto.metrics;

import java.time.LocalDateTime;

/** 实时动态一条（kind：session / transfer / csat / ticket / alert） */
public record ActivityVO(String kind, String text, LocalDateTime eventTime) {
}
