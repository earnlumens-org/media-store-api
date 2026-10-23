package org.earnlumens.mediastore.domain.media.model;

/**
 * What a moderation job is reviewing (MYCELIUM-RESILIENCE.md S-05 / N9).
 * The scope of a rejection is inferred from this, never chosen by a moderator:
 * rejecting a {@link #PENDING_VERSION} discards the staged edit and touches
 * nothing visible ({@code CHANGES_REJECTED}); rejecting {@link #CONTENT} that
 * was live is a takedown ({@code CONTENT_TAKEDOWN}).
 */
public enum ModerationScope {
    /** The live document (first review, resubmission or report escalation). */
    CONTENT,
    /** The {@code Entry.pendingVersion} slot of an already-published entry. */
    PENDING_VERSION
}
