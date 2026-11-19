package io.supportflow.customer.security;

/** Only the authenticated HTTP boundary may enter the dedicated platform DB channel. */
public final class PlatformAccess {
    private static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();
    private PlatformAccess() {}
    public static boolean active() { return ACTIVE.isBound() && ACTIVE.get(); }
    static <T, X extends Throwable> T call(java.lang.ScopedValue.CallableOp<T, X> action) throws X {
        return ScopedValue.where(ACTIVE, true).call(action);
    }
}
