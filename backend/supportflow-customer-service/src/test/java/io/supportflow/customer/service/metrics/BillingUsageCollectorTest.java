package io.supportflow.customer.service.metrics;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BillingUsageCollectorTest {
    @Test void concurrentIncrementsAndDrainsDoNotLoseOrDuplicateAnyCounter() {
        assertTimeoutPreemptively(Duration.ofSeconds(10),()->{
            var collector = new BillingUsageCollector();
            var start = new CountDownLatch(1);
            long sessions=0,messages=0,calls=0;
            try (var workers = Executors.newFixedThreadPool(8)) {
                var futures = new java.util.ArrayList<Future<?>>();
                for (int i=0;i<8;i++) futures.add(workers.submit(()->{
                    start.await();
                    for(int n=0;n<1000;n++) {
                        collector.countSession("TENANT_A");
                        collector.countMessage("TENANT_A");
                        collector.countAiCall("TENANT_A");
                    }
                    return null;
                }));
                start.countDown();
                while(futures.stream().anyMatch(f->!f.isDone())) {
                    var usage=collector.drain("TENANT_A");
                    sessions+=usage.sessions(); messages+=usage.messages(); calls+=usage.aiCalls();
                    Thread.yield();
                }
                for(var future:futures) future.get(1,TimeUnit.SECONDS);
            }
            var tail=collector.drain("TENANT_A");
            assertEquals(8000,sessions+tail.sessions());
            assertEquals(8000,messages+tail.messages());
            assertEquals(8000,calls+tail.aiCalls());
        });
    }
}
