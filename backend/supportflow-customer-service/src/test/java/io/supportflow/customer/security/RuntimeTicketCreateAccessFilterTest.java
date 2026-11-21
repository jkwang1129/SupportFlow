package io.supportflow.customer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.customer.service.bot.*;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTicketCreateAccessFilterTest {
    private static final String PATH=RuntimeSourceWire.PATH.replace("/snapshot","/ticket-create");
    private static final long NOW=1700000000;
    private static final ObjectMapper JSON=new ObjectMapper();
    private ObjectNode body(String title,String content){
        var args=new AgentTicketArguments("TENANT_A",UUID.fromString("00000000-0000-0000-0000-000000000002"),UUID.fromString("00000000-0000-0000-0000-000000000001"),UUID.fromString("00000000-0000-0000-0000-000000000003"),title,content);
        return JSON.createObjectNode().put("snapshot_id",args.snapshotId().toString()).put("owner","ticket-worker").put("fence",1)
            .put("arguments_sha256",args.argumentsSha256()).put("title",title).put("content",content).put("version",AgentTicketArguments.VERSION);
    }
    private MockHttpServletRequest request(String method,String path,String body){
        var request=new MockHttpServletRequest(method,path);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);request.setContent(bytes);request.setContentType("application/json");
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential(method,path,"TENANT_A",bytes,NOW+20));return request;
    }
    private RuntimeSourceAccessFilter filter(boolean read,boolean write){
        return new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,true,true,read,write,List.of(),Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));
    }
    private int reject(MockHttpServletRequest request)throws Exception{
        var response=new MockHttpServletResponse();filter(true,true).doFilter(request,response,(a,b)->fail("Invalid request reached business code"));
        assertNull(request.getAttribute(RuntimeCustomerCall.ATTRIBUTE));return response.getStatus();
    }
    @Test void authenticatedMaximumTextIsBoundToTicketOperationAndTrustedReferences()throws Exception{
        var body=body("🙂".repeat(128),"x".repeat(8192));var request=request("POST",PATH,body.toString());var reached=new AtomicBoolean();
        filter(false,true).doFilter(request,new MockHttpServletResponse(),(a,b)->{
            var command=(RuntimeTicketCreateCommand)a.getAttribute(RuntimeTicketCreateCommand.ATTRIBUTE);assertNotNull(command);
            assertEquals(AgentActionPermit.Operation.TICKET_CREATE,command.action().operation());assertEquals(command.ticket().argumentsSha256(),command.action().argumentsSha256());
            assertEquals(128,command.ticket().title().codePointCount(0,command.ticket().title().length()));assertFalse(command.toString().contains("xxx"));reached.set(true);
        });assertTrue(reached.get());
    }
    @Test void disabledWriteRejectsBeforeReadingBodyAndReadRemainsIndependent()throws Exception{
        var request=new MockHttpServletRequest("POST",PATH){@Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("Disabled API read body");}};
        byte[] bytes=body("title","content").toString().getBytes(StandardCharsets.UTF_8);
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential("POST",PATH,"TENANT_A",bytes,NOW+20));
        var response=new MockHttpServletResponse();filter(true,false).doFilter(request,response,(a,b)->fail());assertEquals(503,response.getStatus());
        var readReached=new AtomicBoolean();filter(true,false).doFilter(request("GET",PATH,""),new MockHttpServletResponse(),(a,b)->readReached.set(true));assertTrue(readReached.get());
        response=new MockHttpServletResponse();filter(false,true).doFilter(request("GET",PATH,""),response,(a,b)->fail());assertEquals(503,response.getStatus());
    }
    @Test void closedFieldsCannotOverrideAuthorityOrBusinessPolicy()throws Exception{
        for(String field:new String[]{"tenant_code","customer_id","session_id","priority","category","assignee_id","operation","actor","source_channel"})
            assertEquals(422,reject(request("POST",PATH,body("title","content").put(field,"untrusted").toString())),field);
        for(var invalid:new ObjectNode[]{body("title","content").put("version","reply-v1"),body("title","content").put("fence",true),
                body("title","content").put("fence","1"),body("title","content").put("fence",0),body("title","content").put("fence",new java.math.BigInteger("9223372036854775808")),
                body("title","content").put("owner","../worker"),body("title","content").put("snapshot_id","ABCDEF00-0000-0000-0000-000000000003"),
                body("title","content").put("arguments_sha256","A".repeat(64)),body("title","content").put("title","changed"),body("title","content").put("content","changed"),
                body("title","content").put("title"," "),body("title","content").put("title","a".repeat(129)),body("title","content").put("content","中".repeat(2731)),
                body("title","content").put("content","a\0b"),body("title","content").putNull("title")})
            assertEquals(422,reject(request("POST",PATH,invalid.toString())));
    }
    @Test void duplicateMalformedOversizedAndGetBodiesAreRejected()throws Exception{
        assertEquals(413,reject(request("POST",PATH," ".repeat(65537))));
        for(String invalid:new String[]{"{","[]","{}{}",body("title","content").toString().replace("\"fence\":1","\"fence\":1,\"fence\":1")})
            assertEquals(400,reject(request("POST",PATH,invalid)));
        assertEquals(400,reject(request("GET",PATH,"{}")));
        var request=request("POST",PATH,body("title","content").toString());request.addHeader("Content-Type","text/plain");assertEquals(415,reject(request));
    }
    @Test void missingTamperedAndCrossRouteCredentialsCannotReachCreation()throws Exception{
        assertEquals(401,reject(new MockHttpServletRequest("POST",PATH)));
        var request=request("POST",PATH,body("title","content").toString());request.setContent(body("other","content").toString().getBytes(StandardCharsets.UTF_8));assertEquals(401,reject(request));
        request=request("POST",PATH.replace("ticket-create","reply"),body("title","content").toString());request.setRequestURI(PATH);assertEquals(401,reject(request));
        request=request("POST",PATH,body("title","content").toString());request.addHeader("X-Tenant-Code","TENANT_B");assertEquals(401,reject(request));
        assertEquals(403,reject(request("DELETE",PATH,"")));
    }
    @Test void existingTicketNumbersCreateAndResultAreStillReadOnlyResourcePaths()throws Exception{
        for(String number:new String[]{"create","result","ticket-create"}){
            String path=PATH.replace("ticket-create","tickets/"+number);var reached=new AtomicBoolean();
            filter(true,true).doFilter(request("GET",path,""),new MockHttpServletResponse(),(a,b)->{
                assertNull(a.getAttribute(RuntimeTicketCreateCommand.ATTRIBUTE));reached.set(true);
            });assertTrue(reached.get());assertEquals(403,reject(request("POST",path,body("title","content").toString())));
        }
    }
    @Test void writeOnlyEnablementRequiresUsableIndependentCredential(){
        assertThrows(IllegalArgumentException.class,()->new RuntimeSourceAccessFilter("short",false,false,false,true,List.of(),Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,()->new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,false,false,true,List.of(RuntimeSourceWire.KEY),Clock.systemUTC()));
    }
}
