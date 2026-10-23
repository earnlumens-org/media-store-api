package org.earnlumens.mediastore.domain.media.model;

/** Kind of moderation action that blocks content (DATA-MODEL.md §7d). */
public enum ModerationCaseKind {
    /** Content is illegal, stolen or violates policy — D-13 {@code CONTENT_TAKEDOWN}. */
    TAKEDOWN,
    /** High-severity reports or an illegal pending edit; blocked until a human reviews it. */
    PREVENTIVE
}
