package org.earnlumens.mediastore.domain.media.model;

/**
 * Moderation decision issued by the moderation pipeline.
 */
public enum ModerationDecision {
    /** Content approved — proceed to transcoding / publishing. */
    APPROVE,
    /** Content rejected — blocked from publishing. */
    REJECT,
    /** Uncertain — queued for manual human review. */
    MANUAL_QUEUE
}
