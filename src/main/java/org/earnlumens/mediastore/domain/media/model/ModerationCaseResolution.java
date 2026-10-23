package org.earnlumens.mediastore.domain.media.model;

/** How a moderation case ended (DATA-MODEL.md §7d). */
public enum ModerationCaseResolution {
    /** Every blocked item of the case was reinstated; access returned automatically. */
    REINSTATED,
    /** The takedown was confirmed. */
    UPHELD
}
