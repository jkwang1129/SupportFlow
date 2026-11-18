package io.supportflow.customer.dto.metrics;

/** 一个租户的增量 */
public record Usage(long sessions, long messages, long aiCalls) {

    public boolean isEmpty() {
        return sessions == 0 && messages == 0 && aiCalls == 0;
    }
}
