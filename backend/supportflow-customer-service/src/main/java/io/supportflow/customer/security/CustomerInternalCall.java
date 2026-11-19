package io.supportflow.customer.security;

import io.supportflow.common.context.TenantContext;

import java.util.function.Supplier;

/** Request attribute set only after service authentication. Bind before entering business transactions. */
public record CustomerInternalCall(String tenant) {
    public static final String ATTRIBUTE = "supportflowVerifiedCustomerCall";

    public <R> R inTenant(String declaredTenant, Supplier<R> operation) {
        if (tenant == null || !tenant.equals(declaredTenant)) {
            throw new ScopeMismatch();
        }
        return TenantContext.bind(tenant, operation::get);
    }

    static final class ScopeMismatch extends RuntimeException {
    }
}
