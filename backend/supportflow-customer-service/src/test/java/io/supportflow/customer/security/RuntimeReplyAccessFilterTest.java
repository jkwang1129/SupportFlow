package io.supportflow.customer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.customer.service.bot.AgentReplyArguments;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeReplyAccessFilterTest {
    static final String PATH=RuntimeSourceWire.PATH.replace("/snapshot","/reply");
    static final long NOW=1700000000;
    static final ObjectMapper JSON=new ObjectMapper();
    private ObjectNode body(String content) {
        var reply=new AgentReplyArguments("TENANT_A",UUID.fromString("00000000-0000-0000-0000-000000000002"),UUID.fromString("00000000-0000-0000-0000-000000000001"),UUID.fromString("00000000-0000-0000-0000-000000000003"),content);
        return JSON.createObjectNode().put("snapshot_id",reply.snapshotId().toString()).put("owner","worker-1").put("fence",1)
                .put("arguments_sha256",reply.argumentsSha256()).put("content",content).put("version","reply-v1");
    }
    private MockHttpServletRequest request(String method,String path,String body) {
        var request=new MockHttpServletRequest(method,path);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);request.setContent(bytes);
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential(method,path,"TENANT_A",bytes,NOW+20));
        request.setContentType("application/json");return request;
    }
    private RuntimeSourceAccessFilter filter(boolean source,boolean result) {
        return new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,source,result,List.of(),Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));
    }
    private int reject(MockHttpServletRequest request) throws Exception {
        var response=new MockHttpServletResponse();filter(true,true).doFilter(request,response,(a,b)->fail("Invalid request reached business code"));
        assertNull(request.getAttribute(RuntimeCustomerCall.ATTRIBUTE));return response.getStatus();
    }
    @Test void replyBodyIsAuthenticatedParsedAndDigestBoundWithoutReplayingTheBody() throws Exception {
        var request=request("POST",PATH,body("x".repeat(8192)).toString());var called=new AtomicBoolean();
        filter(false,true).doFilter(request,new MockHttpServletResponse(),(a,b)->{
            var command=(RuntimeReplyCommand)a.getAttribute(RuntimeReplyCommand.ATTRIBUTE);
            assertEquals(8192,command.reply().content().length());assertEquals(command.reply().argumentsSha256(),command.action().argumentsSha256());
            assertEquals("TENANT_A",((RuntimeCustomerCall)a.getAttribute(RuntimeCustomerCall.ATTRIBUTE)).tenant());called.set(true);
        });assertTrue(called.get());
    }
    @Test void sourceAndResultSwitchesAreIndependentAndDisabledResultNeverReadsBody() throws Exception {
        var bytes=body("reply").toString().getBytes(StandardCharsets.UTF_8);
        var request=new MockHttpServletRequest("POST",PATH){@Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("Disabled result read body");}};
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential("POST",PATH,"TENANT_A",bytes,NOW+20));
        var response=new MockHttpServletResponse();filter(true,false).doFilter(request,response,(a,b)->fail());assertEquals(503,response.getStatus());
        response=new MockHttpServletResponse();filter(false,true).doFilter(request("GET",RuntimeSourceWire.PATH,""),response,(a,b)->fail());assertEquals(503,response.getStatus());
    }
    @Test void schemaCannotChangeTenantRoleOperationVersionOrExecutionAuthority() throws Exception {
        for (var invalid:new ObjectNode[]{body("reply").put("tenant_code","TENANT_B"),body("reply").put("sender_type",2),body("reply").put("operation","ticket.create"),
                body("reply").put("version","reply-v2"),body("reply").put("fence",true),body("reply").put("fence","1"),body("reply").put("fence",0),
                body("reply").put("fence",new java.math.BigInteger("9223372036854775808")),body("reply").put("owner","../worker"),
                body("reply").put("snapshot_id","ABCDEF00-0000-0000-0000-000000000003"),body("reply").put("arguments_sha256","A".repeat(64)),
                body("reply").put("content","changed"),body("reply").put("content"," "),body("reply").put("content","a\0b"),body("reply").put("content","中".repeat(2731))})
            assertEquals(422,reject(request("POST",PATH,invalid.toString())));
    }
    @Test void oversizedMalformedDuplicateAndGetBodiesAreRejected() throws Exception {
        assertEquals(413,reject(request("POST",PATH," ".repeat(65537))));
        for(String invalid:new String[]{"{","{}{}","[]",body("reply").toString().replace("\"fence\":1","\"fence\":1,\"fence\":1")})
            assertEquals(400,reject(request("POST",PATH,invalid)));
        assertEquals(400,reject(request("GET",PATH,"{}")));
        var request=request("POST",PATH,body("reply").toString());request.addHeader("Content-Type","text/plain");assertEquals(415,reject(request));
    }
    @Test void anonymousWrongBodyAndSnapshotCredentialsCannotReachResultCode() throws Exception {
        assertEquals(401,reject(new MockHttpServletRequest("POST",PATH)));
        var request=request("POST",PATH,body("reply").toString());request.setContent(body("different").toString().getBytes(StandardCharsets.UTF_8));assertEquals(401,reject(request));
        request=request("POST",RuntimeSourceWire.PATH,"{}");request.setRequestURI(PATH);assertEquals(401,reject(request));
        assertEquals(403,reject(request("DELETE",PATH,"")));
    }
    @Test void requestExpiringDuringBodyReceptionCannotReachAnyRuntimeController() throws Exception {
        var now=new AtomicReference<>(Instant.ofEpochSecond(NOW));
        var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        var request=new MockHttpServletRequest("POST",PATH){@Override public jakarta.servlet.ServletInputStream getInputStream(){now.set(Instant.ofEpochSecond(NOW+21));return super.getInputStream();}};
        byte[] bytes=body("reply").toString().getBytes(StandardCharsets.UTF_8);request.setContent(bytes);request.setContentType("application/json");
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential("POST",PATH,"TENANT_A",bytes,NOW+20));
        var response=new MockHttpServletResponse();new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,true,List.of(),clock).doFilter(request,response,(a,b)->fail());
        assertEquals(401,response.getStatus());assertNull(request.getAttribute(RuntimeCustomerCall.ATTRIBUTE));
    }
    @Test void resultOnlyEnablementRequiresAnIndependentUsableCredential() {
        assertThrows(IllegalArgumentException.class,()->new RuntimeSourceAccessFilter("short",false,true,List.of(),Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,()->new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,true,List.of(RuntimeSourceWire.KEY),Clock.systemUTC()));
    }
}
