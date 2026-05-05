package org.earnlumens.mediastore.domain.media.model;

public enum EntryStatus {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    PUBLISHED,
    REJECTED,
    SUSPENDED,
    UNLISTED,
    ARCHIVED,
    /**
     * Soft-deleted by the creator. Behaves like {@link #ARCHIVED} for visibility
     * (only the owner and prior buyers with an active entitlement can still see
     * it), but is presented to the creator as a separate "Deleted" bucket so it
     * does not mix with content they merely archived for later use. Reversible
     * via {@code restoreDeletedEntry}, which restores the previous status.
     */
    DELETED
}
