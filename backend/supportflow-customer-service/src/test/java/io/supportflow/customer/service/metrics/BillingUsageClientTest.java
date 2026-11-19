package io.supportflow.customer.service.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.supportflow.common.auth.UsageBatchCredential;
import io.supportflow.common.billing.UsageBatch;
import io.supportflow.customer.internal.BillingUsageClient;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BillingUsageClientTest {
    private static final String SECRET = "synthetic-billing-credential-000000000000000000000";
    private HttpServer server;
    private BillingUsageClient client;
    private UsageBatch batch;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), received = new AtomicInteger();
    private final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

    @BeforeEach void start() throws Exception {
        batch = new UsageBatch(UUID.randomUUID().toString(),"TENANT_A",LocalDate.of(2026,11,18),1,2,3,4,new BigDecimal("1.25"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(UsageBatchCredential.PATH,exchange -> {
            try {
                received.incrementAndGet();
                assertEquals("POST",exchange.getRequestMethod());
                var json = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                var decoded = new UsageBatch(json.get("batchId").asText(),json.get("tenantCode").asText(),
                        LocalDate.parse(json.get("statDate").asText()),json.get("sessions").asLong(),json.get("messages").asLong(),
                        json.get("aiCalls").asLong(),json.get("seats").asInt(),json.get("storageGb").decimalValue());
                assertEquals(batch,decoded);
                assertTrue(UsageBatchCredential.verify(exchange.getRequestHeaders().getFirst(UsageBatchCredential.HEADER),SECRET,decoded,Clock.systemUTC()));
                if (status.get() == 0) return; // connection lost after receiving the complete request
                byte[] data = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status.get(),data.length);
                exchange.getResponseBody().write(data);
            } catch (Throwable failure) {
                errors.add(failure);
            } finally { exchange.close(); }
        });
        server.start();
        client = new BillingUsageClient("http://127.0.0.1:"+server.getAddress().getPort(),SECRET);
    }
    @AfterEach void stop() { server.stop(0); assertTrue(errors.isEmpty(),errors.toString()); }

    @Test void matchingBusinessReceiptIsRequiredForAcknowledgement() {
        body.set("{\"code\":0,\"data\":{\"batchId\":\""+batch.batchId()+"\",\"accepted\":true}}");
        assertTrue(client.report(batch));
        assertEquals(1,received.get());
    }

    @Test void httpSuccessWithBusinessFailureWrongBatchOrMalformedBodyIsNotAnAcknowledgement() {
        for (String response : new String[]{"{\"code\":40901}","{\"code\":0}","not json",
                "{\"code\":0,\"data\":{\"batchId\":\"foreign\",\"accepted\":true}}"}) {
            body.set(response);
            assertFalse(client.report(batch));
        }
        assertEquals(4,received.get());
    }

    @Test void lostResponseAndHttpErrorsRemainRetryablePendingWork() {
        status.set(0);
        assertFalse(client.report(batch));
        assertTrue(received.get() >= 1);
        status.set(503);
        body.set("{\"code\":50000}");
        assertFalse(client.report(batch));
    }

    @Test void absentSigningSecretDoesNotSendAnUnsignedRequest() {
        var unsigned = new BillingUsageClient("http://127.0.0.1:"+server.getAddress().getPort(),"");
        assertFalse(unsigned.report(batch));
        assertEquals(0,received.get());
    }
}
