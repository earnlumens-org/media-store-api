package org.earnlumens.mediastore.infrastructure.tenant;

/**
 * Holds the current tenant ID in a ThreadLocal, set automatically by {@link TenantFilter}.
 * <p>
 * All code that needs the tenant should call {@link #require()} which throws
 * {@link MissingTenantException} if the context was never set for the current request.
 * This makes it impossible to accidentally run a query without tenant isolation.
 * <p>
 * For platform-level background operations (scheduler, watchdog) that legitimately
 * need to operate across tenants, use {@link #runWithoutTenant(Runnable)} to
 * explicitly opt out — this leaves an auditable trace in the code.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CROSS_TENANT_ALLOWED = ThreadLocal.withInitial(() -> false);

    private TenantContext() {
    }

    /** Set the tenant for the current thread (called by TenantFilter). */
    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * Returns the current tenant ID or throws {@link MissingTenantException}.
     * This is the primary method all tenant-scoped code should use.
     */
    public static String require() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null && !CROSS_TENANT_ALLOWED.get()) {
            throw new MissingTenantException(
                    "TenantContext is not set. This indicates a code path that bypasses the TenantFilter "
                            + "or a background job that forgot to call TenantContext.runWithoutTenant().");
        }
        return tenantId;
    }

    /** Returns the current tenant ID or null (for checking without throwing). */
    public static String get() {
        return CURRENT_TENANT.get();
    }

    /** Clear the tenant (called by TenantFilter after request completes). */
    public static void clear() {
        CURRENT_TENANT.remove();
        CROSS_TENANT_ALLOWED.remove();
    }

    /** Returns true if cross-tenant access is currently allowed on this thread. */
    public static boolean isCrossTenantAllowed() {
        return CROSS_TENANT_ALLOWED.get();
    }

    /**
     * Executes a block of code that is explicitly allowed to operate across tenants.
     * Use only for platform-level operations (cleanup, watchdog, dispatch).
     * <p>
     * The cross-tenant flag is scoped to the runnable and automatically cleared after.
     */
    public static void runWithoutTenant(Runnable action) {
        Boolean previous = CROSS_TENANT_ALLOWED.get();
        CROSS_TENANT_ALLOWED.set(true);
        try {
            action.run();
        } finally {
            CROSS_TENANT_ALLOWED.set(previous);
        }
    }
}
