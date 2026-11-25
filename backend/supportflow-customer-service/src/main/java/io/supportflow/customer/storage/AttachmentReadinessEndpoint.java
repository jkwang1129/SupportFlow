package io.supportflow.customer.storage;

import io.supportflow.common.readiness.SchemaReadiness;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Management probe for traffic that requires the attachment bucket. */
@Component
@WebEndpoint(id = "attachmentreadiness")
public class AttachmentReadinessEndpoint {
    private final ApplicationAvailability application;
    private final SchemaReadiness schema;
    private final RustFsObjectStorage storage;

    public AttachmentReadinessEndpoint(ApplicationAvailability application, SchemaReadiness schema,
                                       RustFsObjectStorage storage) {
        this.application = application;
        this.schema = schema;
        this.storage = storage;
    }

    @ReadOperation
    public WebEndpointResponse<Map<String, String>> readiness() {
        boolean ready = application.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC
                && schema.ready() && storage.bucketAvailable();
        return new WebEndpointResponse<>(Map.of("status", ready ? "UP" : "DOWN"), ready ? 200 : 503);
    }
}
