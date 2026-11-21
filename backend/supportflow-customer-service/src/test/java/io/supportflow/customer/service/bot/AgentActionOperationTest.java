package io.supportflow.customer.service.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.supportflow.customer.testkit.RuntimeReceiverStub;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AgentActionOperationTest {
    private static final String KEY=AgentRuntimeClientTest.KEY;
    private static AgentReplyArguments refs(){return new AgentReplyArguments("TENANT_A",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"synthetic shared hash input");}
    private static AgentActionPermit.Request request(AgentReplyArguments refs,AgentActionPermit.Operation operation){
        return new AgentActionPermit.Request(refs.tenant(),refs.runId(),refs.eventId(),"reply-worker",1,refs.snapshotId(),refs.argumentsSha256(),operation);
    }
    @Test void validMacWithIdenticalOtherReferencesCannotCrossOperationBoundary()throws Exception{
        var refs=refs();var expiry=Instant.now().plusSeconds(4);var now=expiry.minusSeconds(4);
        var json=new ObjectMapper();
        var reply=json.readTree(AgentActionPermitFixtures.grant(refs,"reply-worker",1,expiry,KEY,AgentActionPermit.Operation.SESSION_REPLY));
        var ticket=json.readTree(AgentActionPermitFixtures.grant(refs,"reply-worker",1,expiry,KEY,AgentActionPermit.Operation.TICKET_CREATE));
        var replyRequest=request(refs,AgentActionPermit.Operation.SESSION_REPLY);var ticketRequest=request(refs,AgentActionPermit.Operation.TICKET_CREATE);
        assertEquals(replyRequest,AgentActionPermit.verify(reply,replyRequest,KEY,now).request());
        assertEquals(ticketRequest,AgentActionPermit.verify(ticket,ticketRequest,KEY,now).request());
        assertThrows(IllegalArgumentException.class,()->AgentActionPermit.verify(ticket,replyRequest,KEY,now));
        assertThrows(IllegalArgumentException.class,()->AgentActionPermit.verify(reply,ticketRequest,KEY,now));
        assertEquals(AgentActionPermit.Operation.SESSION_REPLY,new AgentActionPermit.Request(refs.tenant(),refs.runId(),refs.eventId(),"reply-worker",1,refs.snapshotId(),refs.argumentsSha256()).operation());
    }
    @Test void narrowClientRejectsWrongTypedRequestBeforeNetwork()throws Exception{
        var refs=refs();
        try(var receiver=new RuntimeReceiverStub(req->{throw new AssertionError("Wrong operation reached network");});var client=new AgentRuntimeClient(receiver.endpoint(),KEY)){
            assertThrows(IllegalArgumentException.class,()->client.authorizeReply(request(refs,AgentActionPermit.Operation.TICKET_CREATE)));
            assertThrows(IllegalArgumentException.class,()->client.authorizeTicketCreate(request(refs,AgentActionPermit.Operation.SESSION_REPLY)));
            assertEquals(0,receiver.calls.get());
        }
        assertThrows(IllegalArgumentException.class,()->request(refs,null));
    }
    @Test void actualHttpTicketRequestUsesOnlyFixedOperationVersionAndSignedReferences()throws Exception{
        var refs=refs();var action=request(refs,AgentActionPermit.Operation.TICKET_CREATE);var json=new ObjectMapper();
        try(var receiver=new RuntimeReceiverStub(req->{
            var body=json.readTree(req.body());assertEquals(8,body.size());
            assertEquals("ticket.create",body.path("operation").textValue());assertEquals("ticket-create-v1",body.path("version").textValue());
            assertEquals(refs.argumentsSha256(),body.path("arguments_sha256").textValue());
            return new RuntimeReceiverStub.Reply(200,AgentActionPermitFixtures.grant(refs,"reply-worker",1,Instant.now().plusSeconds(4),KEY,AgentActionPermit.Operation.TICKET_CREATE));
        });var client=new AgentRuntimeClient(receiver.endpoint(),KEY)){
            assertEquals(action,client.authorizeTicketCreate(action).request());assertEquals(1,receiver.calls.get());assertNull(receiver.failure.get());
        }
    }
}
