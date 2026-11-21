package io.supportflow.customer.service.bot;

import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
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
}
