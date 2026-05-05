package org.earnlumens.mediastore.domain.media.model;

public enum CollectionStatus {
    DRAFT,
    IN_REVIEW,
    PUBLISHED,
    REJECTED,
    ARCHIVED,
    /** Soft-deleted by the creator. Visibility rules mirror ARCHIVED. */
    DELETED
}
