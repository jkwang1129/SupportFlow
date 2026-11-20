package io.supportflow.customer.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.supportflow.customer.service.bot.AgentActionPermit;
import io.supportflow.customer.service.bot.AgentReplyArguments;
import java.util.Set;
import java.util.UUID;

/** Parsed once, after exact-wire authentication; raw request bodies are never replayed or logged. */
public record RuntimeReplyCommand(AgentReplyArguments reply, AgentActionPermit.Request action) {
    public static final String ATTRIBUTE="supportflowVerifiedRuntimeReplyCommand";
    private static final Set<String> FIELDS=Set.of("snapshot_id","owner","fence","arguments_sha256","content","version");
    static RuntimeReplyCommand parse(JsonNode root,RuntimeCustomerCall call) {
        if (!root.isObject() || root.size()!=FIELDS.size()) throw invalid();
        root.fieldNames().forEachRemaining(key->{if (!FIELDS.contains(key)) throw invalid();});
        String rawSnapshot=text(root,"snapshot_id"); UUID snapshot=UUID.fromString(rawSnapshot);
        if (!snapshot.toString().equals(rawSnapshot) || !"reply-v1".equals(text(root,"version"))) throw invalid();
        var fence=root.get("fence");
        if (fence==null || !fence.isIntegralNumber() || !fence.canConvertToLong() || fence.longValue()<=0) throw invalid();
        var reply=new AgentReplyArguments(call.tenant(),call.runId(),call.eventId(),snapshot,text(root,"content"));
        var action=new AgentActionPermit.Request(call.tenant(),call.runId(),call.eventId(),text(root,"owner"),fence.longValue(),snapshot,text(root,"arguments_sha256"));
        if (!reply.argumentsSha256().equals(action.argumentsSha256())) throw invalid();
        return new RuntimeReplyCommand(reply,action);
    }
    private static String text(JsonNode root,String key) {
        var value=root.get(key);if (value==null || !value.isTextual()) throw invalid();return value.textValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid runtime reply command"); }
}
