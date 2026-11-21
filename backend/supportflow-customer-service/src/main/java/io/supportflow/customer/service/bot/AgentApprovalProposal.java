package io.supportflow.customer.service.bot;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Exact proposal returned only after staff and current source checks. */
public record AgentApprovalProposal(UUID approvalId, String status, Instant expiresAt, String digest,
                                    String title, String content) {
    public AgentApprovalProposal {
        if (approvalId == null || !("PENDING".equals(status) || "APPROVED".equals(status))
                || expiresAt == null || !expiresAt.isAfter(Instant.now())
                || digest == null || !digest.matches("[a-f0-9]{64}") || title == null || content == null) {
            throw new IllegalArgumentException("Invalid approval proposal");
        }
    }

    public boolean matches(AgentSourceSnapshots.Snapshot source) {
        var arguments = new AgentTicketArguments(source.tenant(), source.runId(), source.eventId(), source.snapshotId(), title, content);
        return digest.equals(arguments.argumentsSha256());
    }

    public Map<String, String> response() {
        return Map.of("approval_id", approvalId.toString(), "status", status, "expires_at", expiresAt.toString(),
                "arguments_sha256", digest, "title", title, "content", content);
    }

    @Override public String toString() { return "AgentApprovalProposal[references only]"; }
}
