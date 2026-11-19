package io.supportflow.customer.security;

import io.supportflow.common.auth.CustomerInternalCredential.Caller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalCustomerEndpoint {
    Caller value();
    boolean tenantScoped() default true;
}
