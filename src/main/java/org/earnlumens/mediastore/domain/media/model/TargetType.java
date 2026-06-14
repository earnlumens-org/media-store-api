package org.earnlumens.mediastore.domain.media.model;

/**
 * Discriminator for orders and entitlements — whether the target is
 * an individual entry, a collection, or a tip (voluntary creator support).
 *
 * <p>{@code TIP} orders flow through the exact same audited payment pipeline
 * (prepare → sign → submit → confirm → reconcile) but never grant an
 * entitlement: a tip unlocks nothing, it is a direct creator support payment.
 * The tipped entry/collection id is recorded on the order purely as context.
 */
public enum TargetType {
    ENTRY,
    COLLECTION,
    TIP
}
