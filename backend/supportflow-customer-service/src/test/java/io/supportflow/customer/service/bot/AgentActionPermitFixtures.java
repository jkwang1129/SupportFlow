package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/** Synthetic signing for storage tests only; production cannot manufacture permit objects. */
public final class AgentActionPermitFixtures {
    private AgentActionPermitFixtures() { }
    public static AgentActionPermit permit(AgentReplyArguments reply, Instant expires) {
        try {
            String secret = "synthetic-action-storage-test-secret-000000000000";
            var request = new AgentActionPermit.Request(reply.tenant(), reply.runId(), reply.eventId(), "reply-worker", 1,
                    reply.snapshotId(), reply.argumentsSha256());
            var json = new ObjectMapper().createObjectNode().put("version", "action-v1").put("tenant_code", reply.tenant())
                    .put("run_id", reply.runId().toString()).put("source_event_id", reply.eventId().toString()).put("owner", request.owner())
                    .put("fence", 1).put("step_key", "reply-1").put("step_no", 1).put("operation", "session.reply")
                    .put("snapshot_id", reply.snapshotId().toString()).put("arguments_sha256", reply.argumentsSha256()).put("expires_at_ms", expires.toEpochMilli());
            var wire = new StringBuilder("supportflow-agent-action:v1");
            for (String field : new String[]{"version","tenant_code","run_id","source_event_id","owner","fence","step_key","step_no","operation","snapshot_id","arguments_sha256","expires_at_ms"})
                wire.append('\n').append(json.get(field).asText());
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            json.put("signature", HexFormat.of().formatHex(mac.doFinal(wire.toString().getBytes(StandardCharsets.UTF_8))));
            // Test clock can be earlier than wall time, so storage tests can
            // prove the DB clock rejects an originally valid but now old permit.
            return AgentActionPermit.verify(json, request, secret, expires.minusSeconds(4));
        } catch (Exception failure) { throw new IllegalStateException("Synthetic permit fixture failed", failure); }
    }
}
