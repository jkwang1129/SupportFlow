package io.supportflow.customer.service.bot;

import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.supportflow.customer.service.bot.AgentRuntimeClientTest.JSON;
import static io.supportflow.customer.service.bot.AgentRuntimeClientTest.KEY;
import static org.junit.jupiter.api.Assertions.*;

class AgentApprovalReviewTest {
    private static AgentApprovalReview review(AgentApprovalReview.Decision decision) {
        return new AgentApprovalReview("TENANT_A", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), 9007199254740993L, decision);
    }

    @Test void signsAnExactReviewOnTheFixedPathAndAcceptsOnlyMatchingDecision() throws Exception {
        for (var choice : AgentApprovalReview.Decision.values()) {
            var review = review(choice);
            try (var receiver = new RuntimeReceiverStub(request -> {
                assertEquals("POST", request.method());
                assertEquals("/api/ai/v1/runtime/runs/" + review.runId() + "/review-ticket-approval", request.rawPath());
                assertNull(request.headers().get("Authorization"));
                var json = JSON.readTree(request.body());
                assertEquals(JSON.valueToTree(Map.of("approval_id", review.approvalId().toString(),
                        "source_event_id", review.eventId().toString(), "arguments_sha256", review.argumentsSha256(),
                        "reviewer_ref", "staff:9007199254740993", "decision", choice.wire())), json);
                String signed = request.headers().get("X-SupportFlow-AI-Auth").getFirst();
                long expiry = Long.parseLong(signed.split(":")[2]);
                assertEquals(signed, AgentRuntimeProtocol.credential(KEY, expiry, request.method(), request.rawPath(), review.tenant(), request.body()));
                return new RuntimeReceiverStub.Reply(200, JSON.writeValueAsString(Map.of("approval_id", review.approvalId().toString(), "status", choice.result())));
            }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                assertEquals(choice.result(), client.reviewTicketApproval(review));
                assertEquals(1, receiver.calls.get());
                assertNull(receiver.failure.get());
            }
        }
    }

    @Test void foreignOrSubstitutedReviewResultIsRejectedWithoutRetryOrLeakingBody() throws Exception {
        var review = review(AgentApprovalReview.Decision.APPROVE);
        String good = JSON.writeValueAsString(Map.of("approval_id", review.approvalId().toString(), "status", "APPROVED"));
        for (String invalid : new String[]{good + "{}", good.replace("APPROVED", "REVOKED"),
                good.replace(review.approvalId().toString(), UUID.randomUUID().toString()),
                good.replace("\"APPROVED\"", "\"APPROVED\",\"body\":\"private result\""),
                good.replace("\"APPROVED\"", "\"APPROVED\",\"status\":\"APPROVED\"")}) {
            try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, invalid));
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.reviewTicketApproval(review));
                assertEquals("INVALID_APPROVAL_REVIEW_RESULT", failure.code());
                assertFalse(failure.retryable());
                assertEquals(1, receiver.calls.get());
            }
        }
    }

    @Test void validatesReferencesBeforeAnyHttpAndNeverCallsInsideATransaction() throws Exception {
        var review = review(AgentApprovalReview.Decision.DENY);
        assertThrows(IllegalArgumentException.class, () -> new AgentApprovalReview("PLATFORM", review.runId(), review.approvalId(),
                review.eventId(), review.argumentsSha256(), review.reviewerId(), review.decision()));
        assertThrows(IllegalArgumentException.class, () -> new AgentApprovalReview(review.tenant(), review.runId(), review.approvalId(),
                review.eventId(), review.argumentsSha256(), 0, review.decision()));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeProtocol.request(
                URI.create("http://127.0.0.1/api/ai/v1/runtime/runs/" + review.runId() + "/review-ticket-approval"), "GET", review.tenant(), new byte[0], KEY));
        try (var receiver = new RuntimeReceiverStub(request -> fail("Transaction performed review HTTP"));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThrows(IllegalStateException.class, () -> client.reviewTicketApproval(review)); }
            finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
            assertEquals(0, receiver.calls.get());
        }
    }

    @Test void duplicateAdmissionCanAcknowledgeAnExistingWaitingRun() throws Exception {
        var event = AgentRuntimeClientTest.event();
        UUID run = UUID.randomUUID();
        String payload = AgentRuntimeClientTest.receipt(event, run).replace("PENDING", "WAITING_APPROVAL");
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(202, payload));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            assertEquals(new AgentRuntimeClient.Receipt(event.eventId(), run), client.enqueue(event));
            assertEquals(1, receiver.calls.get());
        }
    }

    @Test void readsOnlyTheSignedOriginalProposalAndChecksItsDigest() throws Exception {
        Instant now = Instant.now();
        var source = new AgentSourceSnapshots.Snapshot("TENANT_A", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "source-v1", "{}", "test-hash", now, now.plusSeconds(120));
        var terms = new AgentTicketArguments(source.tenant(), source.runId(), source.eventId(), source.snapshotId(), "标题🙂", "原文\n ");
        UUID approval = UUID.randomUUID();
        String content = JSON.writeValueAsString(Map.of("approval_id", approval.toString(), "status", "PENDING",
                "expires_at", now.plusSeconds(60).toString(), "title", terms.title(), "content", terms.content(), "arguments_sha256", terms.argumentsSha256()));

        try (var receiver = new RuntimeReceiverStub(request -> {
            assertEquals("POST", request.method());
            assertEquals("/api/ai/v1/runtime/runs/" + source.runId() + "/ticket-approval-preview", request.rawPath());
            assertEquals(JSON.valueToTree(Map.of("source_event_id", source.eventId().toString())), JSON.readTree(request.body()));
            assertNull(request.headers().get("Authorization"));
            var signed = request.headers().get("X-SupportFlow-AI-Auth").getFirst();
            long expiry = Long.parseLong(signed.split(":")[2]);
            assertEquals(signed, AgentRuntimeProtocol.credential(KEY, expiry, request.method(), request.rawPath(), source.tenant(), request.body()));
            return new RuntimeReceiverStub.Reply(200, content);
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var proposal = client.previewTicketApproval(source);
            assertEquals(approval, proposal.approvalId());
            assertEquals(terms.content(), proposal.content());
            assertFalse(proposal.toString().contains(terms.title()));
            assertNull(receiver.failure.get());
        }
        for (String invalid : new String[]{content + "{}", content.replace("PENDING", "CONSUMED"),
                content.replace("原文", "替换"), content.replace(terms.argumentsSha256(), "0".repeat(64)),
                content.replace(now.plusSeconds(60).toString(), now.minusSeconds(60).toString())}) {
            try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, invalid));
                 var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
                var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.previewTicketApproval(source));
                assertEquals("INVALID_APPROVAL_PROPOSAL", failure.code());
                assertEquals(1, receiver.calls.get());
            }
        }
    }

    @Test void activeLookupSignsOneReferenceAndRejectsAnExpandedResult() throws Exception {
        String tenant = "TENANT_A", session = "SESSION_A";
        UUID event = UUID.randomUUID(), run = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(60);
        var data = Map.of("source_event_id", event.toString(), "run_id", run.toString(), "status", "PENDING", "expires_at", expiry.toString());
        try (var receiver = new RuntimeReceiverStub(request -> {
            assertEquals("POST", request.method());
            assertEquals("/api/ai/v1/runtime/runs/ticket-approval-active", request.rawPath());
            assertEquals(JSON.valueToTree(Map.of("session_no", session)), JSON.readTree(request.body()));
            String signed = request.headers().get("X-SupportFlow-AI-Auth").getFirst();
            assertEquals(signed, AgentRuntimeProtocol.credential(KEY, Long.parseLong(signed.split(":")[2]),
                    request.method(), request.rawPath(), tenant, request.body()));
            return new RuntimeReceiverStub.Reply(200, JSON.writeValueAsString(data));
        }); var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            assertEquals(new AgentRuntimeClient.ApprovalReference(event, run, "PENDING", expiry), client.activeTicketApproval(tenant, session));
            assertThrows(IllegalArgumentException.class, () -> client.activeTicketApproval(tenant, "bad session"));
            assertEquals(1, receiver.calls.get());
            assertNull(receiver.failure.get());
        }
        var expanded = new java.util.HashMap<>(data);
        expanded.put("content", "private");
        try (var receiver = new RuntimeReceiverStub(request -> new RuntimeReceiverStub.Reply(200, JSON.writeValueAsString(expanded)));
             var client = new AgentRuntimeClient(receiver.endpoint(), KEY)) {
            var failure = assertThrows(AgentRuntimeClient.DeliveryFailure.class, () -> client.activeTicketApproval(tenant, session));
            assertEquals("INVALID_APPROVAL_QUEUE_RESULT", failure.code());
        }
    }
}
