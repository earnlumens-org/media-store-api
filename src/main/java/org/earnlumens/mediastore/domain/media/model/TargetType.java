package org.earnlumens.mediastore.domain.media.model;

/**
 * Discriminator for orders and entitlements — whether the target is
 * an individual entry, a collection, or a tip (voluntary creator support).
 *
 * <p>{@code TIP} orders flow through the exact same audited payment pipeline
 * (prepare → sign → submit → confirm → reconcile) but never grant an
 * entitlement: a tip unlocks nothing, it is a direct creator support payment.
 * The tipped entry/collection id is recorded on the order purely as context.
 *
 * <p>{@code PUBLISH_FEE} and {@code PUBLISH_FAST_PASS} orders also grant no
 * entitlement; on confirmation their effect is applied to the publishing
 * queue (priority-fee accumulation / FastPass slot + item creation) via
 * {@code PublishingQueueService.applyPaymentEffect}.
 */
public enum TargetType {
    ENTRY,
    COLLECTION,
    TIP,
    /** Publish Priority Fee — reorders an existing publishing queue item within its block. */
    PUBLISH_FEE,
    /** FastPass — buys an extra slot in the next publishing block of a space. */
    PUBLISH_FAST_PASS
}
