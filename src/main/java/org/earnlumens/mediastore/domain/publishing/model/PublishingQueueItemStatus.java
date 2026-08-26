package org.earnlumens.mediastore.domain.publishing.model;

/**
 * Lifecycle of a {@link PublishingQueueItem}.
 */
public enum PublishingQueueItemStatus {
    /** Waiting in an OPEN block; fee boosts allowed, cancellable. */
    QUEUED,
    /** Block locked — position frozen, no modifications until publication. */
    LOCKED,
    /** Released to the space feed. Terminal. */
    PUBLISHED,
    /** Removed by the owner while the block was still OPEN (slot freed) or
     *  discarded at publish time because the entity was no longer publishable.
     *  Payments are never reverted. Terminal. */
    CANCELLED
}
