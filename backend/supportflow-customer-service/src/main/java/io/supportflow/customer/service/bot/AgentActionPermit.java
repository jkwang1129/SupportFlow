package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Authenticated execution authority, not a replacement for business resource checks. */
public final class AgentActionPermit {
    public enum Operation {
        SESSION_REPLY("session.reply","reply-v1"), TICKET_CREATE("ticket.create","ticket-create-v1");
        private final String wire,schemaVersion;
        Operation(String wire,String schemaVersion){this.wire=wire;this.schemaVersion=schemaVersion;}
        public String wire(){return wire;}
        public String schemaVersion(){return schemaVersion;}
    }
    public record Request(String tenant, UUID runId, UUID eventId, String owner, long fence,
                          UUID snapshotId, String argumentsSha256,Operation operation) {
        public Request(String tenant,UUID runId,UUID eventId,String owner,long fence,UUID snapshotId,String argumentsSha256){
            this(tenant,runId,eventId,owner,fence,snapshotId,argumentsSha256,Operation.SESSION_REPLY);
        }
        public Request {
            if (tenant == null || !tenant.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,15}") || tenant.equalsIgnoreCase("PLATFORM")
                    || runId == null || eventId == null || snapshotId == null || operation==null || !identifier(owner)
                    || fence <= 0 || argumentsSha256 == null || !argumentsSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid action authority reference");
        }
    }

    private static final Set<String> FIELDS = Set.of("version", "tenant_code", "run_id", "source_event_id", "owner", "fence",
            "step_key", "step_no", "operation", "snapshot_id", "arguments_sha256", "expires_at_ms", "signature");
    private final Request request;
    private final String stepKey;
    private final long stepNo;
    private final Instant expiresAt;

    private AgentActionPermit(Request request, String stepKey, long stepNo, Instant expiresAt) {
        this.request = request; this.stepKey = stepKey; this.stepNo = stepNo; this.expiresAt = expiresAt;
    }

    public Request request() { return request; }
    public String stepKey() { return stepKey; }
    public long stepNo() { return stepNo; }
    public Instant expiresAt() { return expiresAt; }

    /** The write transaction must call this again using its database clock. */
    public void requireValidAt(Instant now) {
        if (now == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(5)))
            throw new IllegalStateException("ACTION_PERMIT_EXPIRED");
    }

    static AgentActionPermit verify(JsonNode root, Request request, String secret, Instant now) {
        if (root == null || !root.isObject() || root.size() != FIELDS.size()) throw invalid();
        root.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) throw invalid(); });
        if (!"action-v1".equals(text(root, "version")) || !request.operation().wire().equals(text(root, "operation"))
                || !request.tenant().equals(text(root, "tenant_code")) || !request.runId().toString().equals(text(root, "run_id"))
                || !request.eventId().toString().equals(text(root, "source_event_id")) || !request.owner().equals(text(root, "owner"))
                || request.fence() != positiveLong(root, "fence") || !request.snapshotId().toString().equals(text(root, "snapshot_id"))
                || !request.argumentsSha256().equals(text(root, "arguments_sha256"))) throw invalid();
        String step = text(root, "step_key"), signature = text(root, "signature");
        long stepNo = positiveLong(root, "step_no"), expires = positiveLong(root, "expires_at_ms");
        if (!identifier(step) || stepNo > 100 || !signature.matches("[0-9a-f]{64}")) throw invalid();
        String canonical = String.join("\n", "supportflow-agent-action:v1", "action-v1", request.tenant(), request.runId().toString(),
                request.eventId().toString(), request.owner(), Long.toString(request.fence()), step, Long.toString(stepNo),
                request.operation().wire(), request.snapshotId().toString(), request.argumentsSha256(), Long.toString(expires));
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw invalid();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(signature)))
                throw invalid();
        } catch (GeneralSecurityException error) { throw invalid(); }
        var permit = new AgentActionPermit(request, step, stepNo, Instant.ofEpochMilli(expires));
        permit.requireValidAt(now);
        return permit;
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"); }
    private static String text(JsonNode root, String field) {
        var value = root.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }
    private static long positiveLong(JsonNode root, String field) {
        var value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw invalid();
        return value.longValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid action permit"); }
}
