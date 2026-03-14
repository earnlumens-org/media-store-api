package org.earnlumens.mediastore.infrastructure.tenant;

/**
 * Thrown when code attempts a tenant-scoped operation but no tenant
 * has been set in {@link TenantContext}.
 * <p>
 * This is a programming error — either the request didn't go through {@link TenantFilter}
 * or a background job forgot to set up tenant context.
 */
public class MissingTenantException extends RuntimeException {

    public MissingTenantException(String message) {
        super(message);
    }
}
