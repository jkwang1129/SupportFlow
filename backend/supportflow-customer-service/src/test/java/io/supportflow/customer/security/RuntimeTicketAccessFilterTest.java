package io.supportflow.customer.security;

import io.supportflow.common.auth.CustomerInternalCredential;
import io.supportflow.customer.testkit.RuntimeSourceWire;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTicketAccessFilterTest {
    private static final long NOW=1700000000;
    private static final String PATH=RuntimeSourceWire.PATH.replace("snapshot","tickets/TICKET-1");
    private RuntimeSourceAccessFilter filter(boolean source,boolean result,boolean read) {
        return new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,source,result,read,List.of(),Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC));
    }
    private MockHttpServletRequest signed(String method,String path,String body) {
        var request=new MockHttpServletRequest(method,path);var bytes=body.getBytes(StandardCharsets.UTF_8);request.setContent(bytes);
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,
                RuntimeSourceWire.credential(method,path,"TENANT_A",bytes,NOW+20));return request;
    }
    private int rejected(MockHttpServletRequest request) throws Exception {
        var response=new MockHttpServletResponse();filter(true,true,true).doFilter(request,response,(a,b)->fail("Invalid ticket call reached business code"));
        assertNull(request.getAttribute(RuntimeCustomerCall.ATTRIBUTE));return response.getStatus();
    }
    @Test void ticketGetHasItsOwnSwitchAndVerifiedScope() throws Exception {
        var request=signed("GET",PATH,"");var called=new AtomicBoolean();
        filter(false,false,true).doFilter(request,new MockHttpServletResponse(),(a,b)->{
            assertEquals("TENANT_A",((RuntimeCustomerCall)a.getAttribute(RuntimeCustomerCall.ATTRIBUTE)).tenant());called.set(true);
        });assertTrue(called.get());
        for(var target:List.of(RuntimeSourceWire.PATH,RuntimeSourceWire.PATH.replace("snapshot","reply"))) {
            var response=new MockHttpServletResponse();filter(false,false,true).doFilter(signed("GET",target,""),response,(a,b)->fail());
            assertEquals(503,response.getStatus());
        }
    }
    @Test void enablingOldApisDoesNotEnableTicketReadsOrReadTheBody() throws Exception {
        var request=new MockHttpServletRequest("GET",PATH){@Override public jakarta.servlet.ServletInputStream getInputStream(){throw new AssertionError("Disabled read consumed body");}};
        request.addHeader("X-Tenant-Code","TENANT_A");request.addHeader(CustomerInternalCredential.HEADER,RuntimeSourceWire.credential("GET",PATH,"TENANT_A",new byte[0],NOW+20));
        var response=new MockHttpServletResponse();filter(true,true,false).doFilter(request,response,(a,b)->fail());assertEquals(503,response.getStatus());
        assertEquals("5",response.getHeader("Retry-After"));
    }
    @Test void postPutDeleteGetBodyAndNoncanonicalResourcePathsAreDenied() throws Exception {
        for(String method:List.of("POST","PUT","DELETE","HEAD")) assertEquals(403,rejected(signed(method,PATH,"")));
        assertEquals(400,rejected(signed("GET",PATH,"{}")));
        assertEquals(413,rejected(signed("GET",PATH,"x".repeat(8193))));
        for(String tail:List.of("","T/1","%2e%2e","T".repeat(65))) {
            String target=RuntimeSourceWire.PATH.replace("snapshot","tickets/"+tail);
            assertEquals(403,rejected(signed("GET",target,"")));
        }
        var query=signed("GET",PATH+"?x=1","");query.setRequestURI(PATH);query.setQueryString("x=1");assertEquals(403,rejected(query));
    }
    @Test void credentialsAreBoundToExactTicketSourceAndBody() throws Exception {
        assertEquals(401,rejected(new MockHttpServletRequest("GET",PATH)));
        var request=signed("GET",PATH,"");request.setRequestURI(PATH.replace("TICKET-1","TICKET-2"));assertEquals(401,rejected(request));
        request=signed("GET",RuntimeSourceWire.PATH,"");request.setRequestURI(PATH);assertEquals(401,rejected(request));
        request=signed("GET",PATH,"");request.setContent("body".getBytes(StandardCharsets.UTF_8));assertEquals(401,rejected(request));
        request=signed("GET",PATH,"");request.addHeader("X-Tenant-Code","TENANT_A");assertEquals(401,rejected(request));
    }
    @Test void readOnlyEnablementRequiresADistinctStrongCredential() {
        assertThrows(IllegalArgumentException.class,()->new RuntimeSourceAccessFilter("short",false,false,true,List.of(),Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,()->new RuntimeSourceAccessFilter(RuntimeSourceWire.KEY,false,false,true,List.of(RuntimeSourceWire.KEY),Clock.systemUTC()));
    }
}
