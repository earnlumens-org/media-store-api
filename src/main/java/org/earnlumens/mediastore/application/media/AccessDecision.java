package org.earnlumens.mediastore.application.media;

/**
 * Outcome of {@link ContentAccessPolicy}. Three distinct denials so the UI
 * never shows a generic 404 to a buyer (DATA-MODEL.md §8.3).
 */
public enum AccessDecision {
    ALLOWED,
    /** Moderation block in force: denied to everyone, owner included, all versions (D-16). */
    DENIED_BLOCKED,
    /** Not publicly visible and the viewer holds no right to it. */
    DENIED_HIDDEN,
    /** Publicly visible but paywalled and not purchased. */
    DENIED_LOCKED;

    public boolean allowed() {
        return this == ALLOWED;
    }
}
