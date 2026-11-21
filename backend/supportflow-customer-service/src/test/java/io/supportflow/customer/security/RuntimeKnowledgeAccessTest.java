package io.supportflow.customer.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeKnowledgeAccessTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final long NOW=1_900_000_000L;
    private static final String PATH="/api/customer/internal/runtime/events/00000000-0000-0000-0000-000000000001/runs/00000000-0000-0000-0000-000000000002/knowledge/search";
    private static ObjectNode body(){
        var node=JSON.createObjectNode();node.put("snapshot_id","00000000-0000-0000-0000-000000000003");node.put("query_sha256","a".repeat(64));
        node.put("embedding_model","fixture:fixture-embedding");node.put("top_k",5);node.putNull("doc_ids");
        var vector=node.putArray("vector");vector.add(.25);for(int i=1;i<1024;i++)vector.add(0.0);return node;
    }
    private static RuntimeSourceAccessFilter filter(boolean enabled){return new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,false,enabled,List.of(),Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));}
    private static MockHttpServletRequest request(String method,byte[] body){
        var request=new MockHttpServletRequest(method,PATH);request.setContent(body);request.setContentType("application/json");
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential(method,PATH,"TENANT_A",body,NOW+20));return request;
    }
    private static int reject(String method,byte[] body,boolean enabled)throws Exception{
        var response=new MockHttpServletResponse();filter(enabled).doFilter(request(method,body),response,(a,b)->fail("Rejected query reached controller"));return response.getStatus();
    }
    @Test void authenticatedPostBindsExactBodyHashAndProducesImmutableHiddenVector()throws Exception{
        byte[] raw=JSON.writeValueAsBytes(body());var request=request("POST",raw);var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        filter(true).doFilter(request,response,(a,b)->{
            called.set(true);var command=(RuntimeKnowledgeCommand)a.getAttribute(RuntimeKnowledgeCommand.ATTRIBUTE);
            assertEquals(1024,command.vector().size());assertEquals(.25,command.vector().getFirst());
            assertEquals(HexFormat.of().formatHex(digest(raw)),command.requestHash());assertFalse(command.toString().contains("0.25"));
            assertThrows(UnsupportedOperationException.class,()->command.vector().set(0,1.0));assertNotNull(a.getAttribute(RuntimeCustomerCall.ATTRIBUTE));
        });assertTrue(called.get());
    }
    @Test void methodsAndDisabledFlagRemainClosed()throws Exception{
        byte[] raw=JSON.writeValueAsBytes(body());assertEquals(403,reject("GET",raw,true));assertEquals(403,reject("PUT",raw,true));
        assertEquals(503,reject("POST",raw,false));
    }
    @Test void callerCannotOverrideTenantPolicyUrlOrSql()throws Exception{
        for(String field:List.of("tenant_code","published_only","scope","url","sql")){
            var value=body();value.put(field,"untrusted");assertEquals(422,reject("POST",JSON.writeValueAsBytes(value),true));
        }
    }
    @Test void strictTypesAndVectorShapeFailBeforeAnyControllerOrDatabase()throws Exception{
        for(String fault:List.of("short","long","zero","tiny","large","boolean","index-list","top-bool","top-wide","label","snapshot","hash")){
            var value=body();var vector=(com.fasterxml.jackson.databind.node.ArrayNode)value.get("vector");
            switch(fault){case "short"->vector.remove(0);case "long"->vector.add(1);case "zero"->vector.set(0,JSON.getNodeFactory().numberNode(0));
                case "tiny"->vector.set(0,JSON.getNodeFactory().numberNode(1e-12));case "large"->vector.set(0,JSON.getNodeFactory().numberNode(1e7));
                case "boolean"->vector.set(0,JSON.getNodeFactory().booleanNode(true));case "index-list"->value.putArray("doc_ids").add(true);
                case "top-bool"->value.put("top_k",true);case "top-wide"->value.put("top_k",6);case "label"->value.put("embedding_model","local-hash");
                case "snapshot"->value.put("snapshot_id","not-a-uuid");case "hash"->value.put("query_sha256","bad");}
            assertEquals(422,reject("POST",JSON.writeValueAsBytes(value),true),fault);
        }
    }
    @Test void bodyTamperingDuplicateJsonAndByteLimitsAreRejected()throws Exception{
        byte[] raw=JSON.writeValueAsBytes(body());var request=request("POST",raw);request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        var response=new MockHttpServletResponse();filter(true).doFilter(request,response,(a,b)->fail());assertEquals(401,response.getStatus());
        assertEquals(413,reject("POST",new byte[65537],true));
        for(String text:List.of("{\"x\":1,\"x\":2}","{}{}","[]"))assertEquals(400,reject("POST",text.getBytes(StandardCharsets.UTF_8),true));
    }
    @Test void canonicalConstructorCannotBeUsedToMakeAMutableOrInvalidCommand(){
        assertThrows(IllegalArgumentException.class,()->RuntimeKnowledgeCommand.parse(null,"a".repeat(64)));
        var value=body();value.putArray("doc_ids").add(9).add(9).add(10);
        var command=RuntimeKnowledgeCommand.parse(value,"b".repeat(64));assertEquals(List.of(9L,10L),command.docIds());
        assertThrows(UnsupportedOperationException.class,()->command.docIds().add(11L));
        assertThrows(IllegalArgumentException.class,()->new RuntimeKnowledgeCommand(UUID.randomUUID(),"a".repeat(64),"fixture:model",List.of(.25),5,null,"b".repeat(64)));
    }
    private static byte[] digest(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(Exception failure){throw new AssertionError(failure);}}
}
