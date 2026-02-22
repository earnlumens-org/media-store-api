package org.earnlumens.mediastore.domain.media.model;

/**
 * Role of a payment split recipient.
 * <p>
 * Currently only PLATFORM + SELLER are used.
 * COLLABORATOR is reserved for future multi-recipient splits
 * (e.g. actors, editors, musicians sharing revenue).
 */
public enum SplitRole {
    PLATFORM,
    SELLER,
    COLLABORATOR
}
