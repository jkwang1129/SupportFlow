package io.supportflow.billing.controller;

import io.supportflow.billing.service.UsageMeterService;
import io.supportflow.common.api.ApiResponse;
import io.supportflow.common.auth.UsageBatchCredential;
import io.supportflow.common.billing.UsageBatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/api/billing/internal/usage")
public class InternalUsageController {
    private final UsageMeterService service;
    private final String secret;
    public InternalUsageController(UsageMeterService service,
            @Value("${SUPPORTFLOW_BILLING_CUSTOMER_SECRET:}") String secret) {
        this.service = service;
        this.secret = secret;
    }

    @PostMapping("/report")
    public ResponseEntity<?> report(@RequestBody UsageBatch batch,
            @RequestHeader(value=UsageBatchCredential.HEADER, required=false) String credential) {
        if (!UsageBatchCredential.verify(credential, secret, batch, Clock.systemUTC())) {
            return ResponseEntity.status(403).body(ApiResponse.fail(40300, "无有效的计费用量上报身份"));
        }
        service.report(batch);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("batchId", batch.batchId(), "accepted", true)));
    }
}
