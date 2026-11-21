package io.supportflow.customer.service.bot;

import java.util.UUID;

/** Reviewer is derived from a current staff identity by the customer service. */
public record AgentApprovalReview(String tenant, UUID runId, UUID approvalId, UUID eventId,
                                  String argumentsSha256, long reviewerId, Decision decision) {
    public enum Decision {
        APPROVE("approve", "APPROVED"), DENY("deny", "DENIED"), REVOKE("revoke", "REVOKED");

        private final String wire;
        private final String result;

        Decision(String wire, String result) {
            this.wire = wire;
            this.result = result;
        }

        public String wire() { return wire; }
        public String result() { return result; }
    }

    public AgentApprovalReview {
        if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                || runId == null || approvalId == null || eventId == null || reviewerId < 1 || decision == null
                || argumentsSha256 == null || !argumentsSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid approval review references");
        }
    }

    public String reviewerRef() { return "staff:" + reviewerId; }
}
