package org.earnlumens.mediastore.domain.media.model;

/**
 * Role of a payment split recipient.
 * <p>
 * PLATFORM     — EarnLumens platform fee (from PlatformConfig / Tenant.platformFeePercent).
 * TENANT       — The tenant operator's own fee (from Tenant.tenantFeePercent), optional.
 * SELLER       — Creator / seller of the entry or collection.
 * COLLABORATOR — Reserved for future multi-recipient splits
 *                (e.g. actors, editors, musicians sharing revenue).
 * FRANCHISE    — A franchise ("beta") commission, carved out of the tenant's
 *                own share when the sale is made through a franchise. The
 *                final price is unchanged; only the tenant's portion is split.
 * RESELLER     — A reseller commission, carved out of the SELLER's own share
 *                when the buyer arrives through a reseller link. The creator
 *                voluntarily gives away this cut to reward real distribution;
 *                the final price and every other split are unchanged.
 */
public enum SplitRole {
    PLATFORM,
    TENANT,
    SELLER,
    COLLABORATOR,
    FRANCHISE,
    RESELLER
}
