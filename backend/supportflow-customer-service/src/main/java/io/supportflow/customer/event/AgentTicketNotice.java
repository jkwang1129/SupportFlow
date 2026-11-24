package io.supportflow.customer.event;

/** Reference to the system message written atomically with an approved ticket. */
public record AgentTicketNotice(String tenant, String sessionNo, long sequence, String messageId) {}
