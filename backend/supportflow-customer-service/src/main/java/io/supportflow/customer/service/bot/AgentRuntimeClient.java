package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A bounded, reference-only delivery operation, with no implicit retry. */
public final class AgentRuntimeClient implements AutoCloseable {
    public record Receipt(UUID eventId, UUID runId) { }
    public record ApprovalReference(UUID eventId, UUID runId, String status, Instant expiresAt) { }
    public record RunProgress(String state, int attempt, int stepCount) { }
    public static final class DeliveryFailure extends RuntimeException {
        private final boolean retryable;
        DeliveryFailure(boolean retryable, String code) { super(code); this.retryable = retryable; }
        public boolean retryable() { return retryable; }
        public String code() { return getMessage(); }
    }
    private static final Set<String> STATES = Set.of("PENDING", "RUNNING", "WAITING_APPROVAL", "RETRY_WAIT", "SUCCEEDED", "FAILED", "CANCELLED");
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client;
    private final URI endpoint;
    private final String secret;
    private final Duration deadline;

    public AgentRuntimeClient(URI endpoint, String secret) { this(endpoint, secret, Duration.ofSeconds(10)); }

    /** Smaller deadlines support deterministic local fault injection. Maximum stays 10s. */
    public AgentRuntimeClient(URI endpoint, String secret, Duration deadline) {
        if (endpoint == null || endpoint.getRawPath() == null
                || !endpoint.getRawPath().matches("(?:/[A-Za-z0-9_-]+)*/ai/v1/runtime/runs"))
            throw new IllegalArgumentException("Expected the runtime admission endpoint");
        if (deadline == null || deadline.compareTo(Duration.ofMillis(100)) < 0 || deadline.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Invalid runtime delivery deadline");
        AgentRuntimeProtocol.request(endpoint, "POST", "CONFIG_CHECK", new byte[0], secret);
        this.endpoint = endpoint; this.secret = secret; this.deadline = deadline;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    public Receipt enqueue(AgentEventOutbox.Event event) {
        final byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of("tenant_code", event.tenant(), "source_event_id", event.eventId().toString(),
                    "session_no", event.sessionNo(), "event_seq", event.sequence(), "message_id", event.messageId(),
                    "principal_ref", event.principalRef(), "trace_id", event.traceId(), "runtime_version", event.runtimeVersion()));
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_SOURCE_REFERENCE"); }
        var request = HttpRequest.newBuilder(AgentRuntimeProtocol.request(endpoint, "POST", event.tenant(), body, secret),
                        (name, value) -> true)
                .header("X-Request-Id", event.traceId()).build();
        var response = exchange(request, "DELIVERY");
        int code = response.statusCode();
        if (code != 202) {
            boolean retry = code == 408 || code == 425 || code == 429 || code >= 500;
            throw new DeliveryFailure(retry, "RECEIVER_HTTP_" + code);
        }
        var contentTypes = response.headers().allValues("Content-Type");
        if (contentTypes.size() != 1 || !contentTypes.getFirst().split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
            throw new DeliveryFailure(false, "INVALID_RECEIPT");
        try {
            JsonNode root = mapper.readTree(response.body());
            if (root == null || !root.isObject() || !event.tenant().equals(text(root, "tenant_code"))
                    || !event.eventId().toString().equals(text(root, "source_event_id")) || !STATES.contains(text(root, "state")))
                throw new IllegalArgumentException();
            return new Receipt(event.eventId(), uuid(root, "run_id"));
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_RECEIPT"); }
    }

    /** Call outside the customer write transaction. Never retries a write or invents a new Run. */
    public AgentActionPermit authorizeReply(AgentActionPermit.Request action) {
        return authorize(action,AgentActionPermit.Operation.SESSION_REPLY);
    }

    public AgentActionPermit authorizeTicketCreate(AgentActionPermit.Request action) {
        return authorize(action,AgentActionPermit.Operation.TICKET_CREATE);
    }

    public String reviewTicketApproval(AgentApprovalReview review) {
        if (review == null) throw new IllegalArgumentException("Approval review reference required");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Approval review must run outside the business transaction");
        final byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of("approval_id", review.approvalId().toString(),
                    "source_event_id", review.eventId().toString(), "arguments_sha256", review.argumentsSha256(),
                    "reviewer_ref", review.reviewerRef(), "decision", review.decision().wire()));
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_APPROVAL_REVIEW"); }
        URI uri = URI.create(endpoint + "/" + review.runId() + "/review-ticket-approval");
        var response = exchange(AgentRuntimeProtocol.request(uri, "POST", review.tenant(), body, secret), "APPROVAL_REVIEW");
        int code = response.statusCode();
        if (code != 200) throw new DeliveryFailure(code == 408 || code == 425 || code == 429 || code >= 500, "APPROVAL_REVIEW_HTTP_" + code);
        if (!hasPlainJsonBody(response)) throw new DeliveryFailure(false, "INVALID_APPROVAL_REVIEW_RESULT");
        try {
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject() || result.size() != 2
                    || !review.approvalId().toString().equals(text(result, "approval_id"))
                    || !review.decision().result().equals(text(result, "status"))) throw new IllegalArgumentException();
            return review.decision().result();
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_APPROVAL_REVIEW_RESULT"); }
    }

    public AgentApprovalProposal previewTicketApproval(AgentSourceSnapshots.Snapshot source) {
        if (source == null) throw new IllegalArgumentException("Verified approval source required");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Approval preview must run outside the business transaction");
        final byte[] body;
        try { body = mapper.writeValueAsBytes(Map.of("source_event_id", source.eventId().toString())); }
        catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_APPROVAL_PREVIEW_REFERENCE"); }
        URI uri = URI.create(endpoint + "/" + source.runId() + "/ticket-approval-preview");
        var response = exchange(AgentRuntimeProtocol.request(uri, "POST", source.tenant(), body, secret), "APPROVAL_PREVIEW", 12288);
        int code = response.statusCode();
        if (code != 200) throw new DeliveryFailure(code == 408 || code == 425 || code == 429 || code >= 500, "APPROVAL_PREVIEW_HTTP_" + code);
        if (!hasPlainJsonBody(response)) throw new DeliveryFailure(false, "INVALID_APPROVAL_PROPOSAL");
        try {
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject() || json.size() != 6) throw new IllegalArgumentException();
            var proposal = new AgentApprovalProposal(uuid(json, "approval_id"), text(json, "status"), Instant.parse(text(json, "expires_at")),
                    text(json, "arguments_sha256"), text(json, "title"), text(json, "content"));
            if (!proposal.matches(source)) throw new IllegalArgumentException();
            return proposal;
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_APPROVAL_PROPOSAL"); }
    }

    public ApprovalReference activeTicketApproval(String tenant, String sessionNo) {
        if (sessionNo == null || !sessionNo.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))
            throw new IllegalArgumentException("Valid session reference required");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Approval lookup must run outside the business transaction");
        final byte[] body;
        try { body = mapper.writeValueAsBytes(Map.of("session_no", sessionNo)); }
        catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_APPROVAL_QUEUE_REFERENCE"); }
        URI uri = URI.create(endpoint + "/ticket-approval-active");
        var response = exchange(AgentRuntimeProtocol.request(uri, "POST", tenant, body, secret), "APPROVAL_QUEUE");
        int code = response.statusCode();
        if (code == 404) return null;
        if (code != 200) throw new DeliveryFailure(code == 408 || code == 425 || code == 429 || code >= 500, "APPROVAL_QUEUE_HTTP_" + code);
        if (!hasPlainJsonBody(response)) throw new DeliveryFailure(false, "INVALID_APPROVAL_QUEUE_RESULT");
        try {
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject() || json.size() != 4) throw new IllegalArgumentException();
            String phase = text(json, "status");
            Instant expiry = Instant.parse(text(json, "expires_at"));
            if (!("PENDING".equals(phase) || "APPROVED".equals(phase)) || !expiry.isAfter(Instant.now()))
                throw new IllegalArgumentException();
            return new ApprovalReference(uuid(json, "source_event_id"), uuid(json, "run_id"), phase, expiry);
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_APPROVAL_QUEUE_RESULT"); }
    }

    public RunProgress approvalProgress(AgentSourceSnapshots.Snapshot source) {
        if (source == null) throw new IllegalArgumentException("Verified progress source required");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Progress lookup must run outside the business transaction");
        URI uri = URI.create(endpoint + "/" + source.runId());
        var response = exchange(AgentRuntimeProtocol.request(uri, "GET", source.tenant(), new byte[0], secret), "APPROVAL_PROGRESS");
        int code = response.statusCode();
        if (code != 200) throw new DeliveryFailure(code == 408 || code == 425 || code == 429 || code >= 500, "APPROVAL_PROGRESS_HTTP_" + code);
        if (!hasPlainJsonBody(response)) throw new DeliveryFailure(false, "INVALID_APPROVAL_PROGRESS");
        try {
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject() || json.size() != 10 || !json.has("model_calls") || !json.has("tool_calls")
                    || !json.has("retry_count") || !json.has("error_code") || !source.tenant().equals(text(json, "tenant_code"))
                    || !source.eventId().equals(uuid(json, "source_event_id")) || !source.runId().equals(uuid(json, "run_id")))
                throw new IllegalArgumentException();
            String state = text(json, "state");
            if (!STATES.contains(state)) throw new IllegalArgumentException();
            return new RunProgress(state, count(json, "attempt", Integer.MAX_VALUE), count(json, "step_count", 100));
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_APPROVAL_PROGRESS"); }
    }

    private AgentActionPermit authorize(AgentActionPermit.Request action,AgentActionPermit.Operation expectedOperation) {
        if (action == null || action.operation()!=expectedOperation) throw new IllegalArgumentException("Matching action reference is required");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Action authority must be fetched outside the business transaction");
        final byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of("tenant_code", action.tenant(), "source_event_id", action.eventId().toString(),
                    "owner", action.owner(), "fence", action.fence(), "snapshot_id", action.snapshotId().toString(),
                    "arguments_sha256", action.argumentsSha256(), "operation", action.operation().wire(), "version", action.operation().schemaVersion()));
        } catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_ACTION_REFERENCE"); }
        URI uri = URI.create(endpoint + "/" + action.runId() + "/authorize");
        var response = exchange(AgentRuntimeProtocol.request(uri, "POST", action.tenant(), body, secret), "AUTHORITY");
        int code = response.statusCode();
        if (code != 200) throw new DeliveryFailure(code == 408 || code == 425 || code == 429 || code >= 500, "AUTHORITY_HTTP_" + code);
        if (!hasPlainJsonBody(response))
            throw new DeliveryFailure(false, "INVALID_ACTION_PERMIT");
        try { return AgentActionPermit.verify(mapper.readTree(response.body()), action, secret, Instant.now()); }
        catch (Exception invalid) { throw new DeliveryFailure(false, "INVALID_ACTION_PERMIT"); }
    }

    private static boolean hasPlainJsonBody(HttpResponse<byte[]> response) {
        var contentTypes = response.headers().allValues("Content-Type");
        var encodings = response.headers().allValues("Content-Encoding");
        return contentTypes.size() == 1 && contentTypes.getFirst().split(";", 2)[0].trim().equalsIgnoreCase("application/json")
                && (encodings.isEmpty() || encodings.size() == 1 && encodings.getFirst().equalsIgnoreCase("identity"));
    }

    private HttpResponse<byte[]> exchange(HttpRequest request, String operation) {
        return exchange(request, operation, 8192);
    }

    private HttpResponse<byte[]> exchange(HttpRequest request, String operation, int maxBodyBytes) {
        var future = client.sendAsync(request, ignored -> new BoundedBody(maxBodyBytes));
        final HttpResponse<byte[]> response;
        try {
            // Covers headers AND body completion; no unbounded InputStream read.
            response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new DeliveryFailure(true, operation + "_TIMEOUT");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DeliveryFailure(true, operation + "_INTERRUPTED");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause())
                if (cause instanceof OversizeBody) throw new DeliveryFailure(false, "RESPONSE_TOO_LARGE");
            throw new DeliveryFailure(true, operation + "_NETWORK_ERROR");
        }
        return response;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    private static UUID uuid(JsonNode node, String key) {
        String raw = text(node, key);
        UUID parsed = UUID.fromString(raw);
        if (!parsed.toString().equals(raw)) throw new IllegalArgumentException();
        return parsed;
    }

    private static int count(JsonNode node, String key, int maximum) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > maximum)
            throw new IllegalArgumentException();
        return value.intValue();
    }

    @Override public void close() { client.shutdownNow(); }

    private static final class OversizeBody extends IOException { }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maxBodyBytes;
        private Flow.Subscription subscription;
        BoundedBody(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > maxBodyBytes - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new OversizeBody()); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
