package io.supportflow.customer.service.bot;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTicketArgumentsTest {
    private static final UUID RUN=UUID.fromString("00000000-0000-0000-0000-000000000001"),EVENT=UUID.fromString("00000000-0000-0000-0000-000000000002"),SNAP=UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static AgentTicketArguments args(String title,String content){return new AgentTicketArguments("TENANT_A",RUN,EVENT,SNAP,title,content);}
    private static String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    @Test void exactTextAndFixedPolicyUseDomainSeparatedHashWithoutNormalization()throws Exception{
        var value=args("标题", "原文\r\nbody ");
        assertEquals("97638edb7b243df0c8c0f35590b7c87cf423d3c4c642d57363000b8a0047dc86",value.argumentsSha256());
        assertEquals(sha(String.join("\n","supportflow-ticket-create:v1","TENANT_A",RUN.toString(),EVENT.toString(),SNAP.toString(),sha("标题"),sha("原文\r\nbody "),"5","2","1")),value.argumentsSha256());
        assertNotEquals(value.argumentsSha256(),args("标题","原文\nbody ").argumentsSha256());
        assertNotEquals(value.argumentsSha256(),args("标题","原文\r\nbody").argumentsSha256());
        assertEquals("agent-run:"+RUN,value.creatorRef());assertFalse(value.toString().contains("原文"));
    }
    @Test void unicodeCodepointByteAndMalformedTextLimitsAreEnforced(){
        for(String title:new String[]{"\u00a0","\u2007","\u202f","\u0085"})args(title,"body");
        for(String title:new String[]{"\u1680","\u2000","\u2028","\u3000","\u001c"})assertThrows(IllegalArgumentException.class,()->args(title,"body"));
        assertEquals(128,args("🙂".repeat(128),"body").title().codePointCount(0,256));
        args("title","x".repeat(8192));
        for(String title:new String[]{null,""," ","x".repeat(129),"🙂".repeat(129),"a\0","\ud800"})assertThrows(IllegalArgumentException.class,()->args(title,"body"));
        for(String body:new String[]{null,""," ","x".repeat(8193),"中".repeat(2731),"a\0","\ud800"})assertThrows(IllegalArgumentException.class,()->args("title",body));
    }
    @Test void platformMissingAndMalformedReferencesAreNotBusinessInputs(){
        for(String tenant:new String[]{null,"","PLATFORM","platform","TENANT A"})assertThrows(IllegalArgumentException.class,()->new AgentTicketArguments(tenant,RUN,EVENT,SNAP,"title","body"));
        assertThrows(IllegalArgumentException.class,()->new AgentTicketArguments("TENANT_A",null,EVENT,SNAP,"title","body"));
    }
    @Test void absentTenantOrBusinessTransactionStopsBeforeAnyDatabaseOrServiceCall(){
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var tickets=mock(io.supportflow.customer.service.ticket.TicketService.class);
        var boundary=new AgentTicketCreations(jdbc,tickets);var value=args("title","body");
        assertThrows(IllegalStateException.class,()->boundary.find(value));
        assertThrows(IllegalStateException.class,()->boundary.create(null,value));
        verifyNoInteractions(jdbc,tickets);
    }
}
