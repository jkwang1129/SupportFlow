package io.supportflow.customer.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.supportflow.customer.service.bot.AgentActionPermit;
import io.supportflow.customer.service.bot.AgentTicketArguments;
import java.util.Set;
import java.util.UUID;

/** Closed, exact-byte authenticated command; authority fields never come from model text. */
public record RuntimeTicketCreateCommand(AgentTicketArguments ticket,AgentActionPermit.Request action) {
    public static final String ATTRIBUTE="supportflowVerifiedRuntimeTicketCreateCommand";
    private static final Set<String> FIELDS=Set.of("snapshot_id","owner","fence","arguments_sha256","title","content","version");
    static RuntimeTicketCreateCommand parse(JsonNode root,RuntimeCustomerCall call){
        if(!root.isObject() || root.size()!=FIELDS.size())throw invalid();
        root.fieldNames().forEachRemaining(key->{if(!FIELDS.contains(key))throw invalid();});
        String raw=text(root,"snapshot_id");UUID snapshot=UUID.fromString(raw);
        if(!snapshot.toString().equals(raw) || !AgentTicketArguments.VERSION.equals(text(root,"version")))throw invalid();
        var fence=root.get("fence");
        if(fence==null || !fence.isIntegralNumber() || !fence.canConvertToLong() || fence.longValue()<=0)throw invalid();
        var ticket=new AgentTicketArguments(call.tenant(),call.runId(),call.eventId(),snapshot,text(root,"title"),text(root,"content"));
        var action=new AgentActionPermit.Request(call.tenant(),call.runId(),call.eventId(),text(root,"owner"),fence.longValue(),snapshot,
            text(root,"arguments_sha256"),AgentActionPermit.Operation.TICKET_CREATE);
        if(!ticket.argumentsSha256().equals(action.argumentsSha256()))throw invalid();
        return new RuntimeTicketCreateCommand(ticket,action);
    }
    private static String text(JsonNode root,String name){var value=root.get(name);if(value==null || !value.isTextual())throw invalid();return value.textValue();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid runtime ticket command");}
    @Override public String toString(){return "RuntimeTicketCreateCommand[references only]";}
}
