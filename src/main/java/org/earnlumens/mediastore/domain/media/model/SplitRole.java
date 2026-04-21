package org.earnlumens.mediastore.domain.media.model;

/**
 * Role of a payment split recipient.
 * <p>
 * PLATFORM     — EarnLumens platform fee (from PlatformConfig / Tenant.platformFeePercent).
 * TENANT       — The tenant operator's own fee (from Tenant.tenantFeePercent), optional.
 * SELLER       — Creator / seller of the entry or collection.
 * COLLABORATOR — Reserved for future multi-recipient splits
 *                (e.g. actors, editors, musicians sharing revenue).
 */
public enum SplitRole {
    PLATFORM,
    TENANT,
    SELLER,
    COLLABORATOR
}
