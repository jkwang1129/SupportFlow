package io.supportflow.customer.security;

import io.supportflow.common.context.TenantContext;
import io.supportflow.common.exception.BizException;

import java.util.UUID;
import java.util.function.Supplier;

/** Created only after the runtime request has passed service authentication. */
public record RuntimeCustomerCall(String tenant, UUID eventId, UUID runId) {
    public static final String ATTRIBUTE = "supportflowVerifiedRuntimeCustomerCall";
    public <T> T inTenant(UUID event, UUID run, Supplier<T> action) {
        if (!eventId.equals(event) || !runId.equals(run)) throw new BizException(40303, "任务资源与服务授权不一致");
        return TenantContext.bind(tenant, action::get);
    }
}
