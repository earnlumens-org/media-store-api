package org.earnlumens.mediastore.domain.publishing.model;

/**
 * Lifecycle of a {@link PublishingBlock}.
 *
 * <pre>
 * OPEN ──(lockAt reached: order frozen)──▶ LOCKED ──(publishAt reached)──▶ PUBLISHED
 * </pre>
 */
public enum PublishingBlockStatus {
    /** Accepting entrants; fees may still change the internal order. */
    OPEN,
    /** Frozen (1 min before publish by default): order fixed, no changes allowed. */
    LOCKED,
    /** All items released to the space feed. Terminal. */
    PUBLISHED
}
