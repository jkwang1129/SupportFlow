package io.supportflow.billing.service;

import io.supportflow.billing.controller.InternalUsageController;
import io.supportflow.common.auth.UsageBatchCredential;
import io.supportflow.common.billing.UsageBatch;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsageBatchAccessTest {
    private static final String SECRET = "synthetic-billing-credential-000000000000000000000";
    private static UsageBatch batch(long messages, String tenant) {
        return new UsageBatch("616a87f1-71af-4b34-9e7f-297927973231",tenant,LocalDate.of(2026,11,18),1,messages,1,0,new BigDecimal("1.00"));
    }

    @Test void onlyAnAuthenticatedUnmodifiedBatchReachesTheService() {
        var service = mock(UsageMeterService.class);
        var controller = new InternalUsageController(service,SECRET);
        var batch = batch(2,"TENANT_A");
        String credential = UsageBatchCredential.sign(SECRET,batch,Clock.systemUTC());
        assertEquals(403,controller.report(batch,null).getStatusCode().value());
        assertEquals(403,controller.report(batch,"broken").getStatusCode().value());
        assertEquals(403,controller.report(batch(3,"TENANT_A"),credential).getStatusCode().value());
        assertEquals(403,controller.report(batch(2,"TENANT_B"),credential).getStatusCode().value());
        verifyNoInteractions(service);
        assertEquals(200,controller.report(batch,credential).getStatusCode().value());
        verify(service).report(batch);
    }

    @Test void expiredFutureAndMissingServerCredentialsAreRejected() {
        var batch = batch(2,"TENANT_A");
        var now = Clock.fixed(Instant.parse("2026-11-19T08:00:00Z"),ZoneOffset.UTC);
        String signed = UsageBatchCredential.sign(SECRET,batch,now);
        assertTrue(UsageBatchCredential.verify(signed,SECRET,batch,now));
        assertFalse(UsageBatchCredential.verify(signed,SECRET,batch,Clock.offset(now,Duration.ofSeconds(30))));
        assertFalse(UsageBatchCredential.verify(signed,SECRET,batch,Clock.offset(now,Duration.ofHours(-1))));
        assertFalse(UsageBatchCredential.verify(signed,"",batch,now));
        assertFalse(UsageBatchCredential.verify(signed,SECRET+"wrong",batch,now));
        var service = mock(UsageMeterService.class);
        assertEquals(403,new InternalUsageController(service,"").report(batch,signed).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test void invalidIdentityDateCountersAndStorageAreRejectedBeforeHashing() {
        String id = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class,()->new UsageBatch("bad","TENANT_A",LocalDate.now(),0,0,0,0,BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->new UsageBatch(id,"PLATFORM",LocalDate.now(),0,0,0,0,BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->new UsageBatch(id,"TENANT_A",null,0,0,0,0,BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->new UsageBatch(id,"TENANT_A",LocalDate.now(),-1,0,0,0,BigDecimal.ZERO));
        assertThrows(ArithmeticException.class,()->new UsageBatch(id,"TENANT_A",LocalDate.now(),0,0,0,0,new BigDecimal("0.001")));
        var normalized = new UsageBatch(id,"TENANT_A",LocalDate.now(),0,0,0,0,new BigDecimal("1"));
        var otherScale = new UsageBatch(id,"TENANT_A",LocalDate.now(),0,0,0,0,new BigDecimal("1.00"));
        assertEquals(normalized.fingerprint(),otherScale.fingerprint());
    }
}
