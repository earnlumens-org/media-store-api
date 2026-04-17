package org.earnlumens.mediastore.domain.media.model;

public enum ReportResolution {
    /** Still awaiting moderator review. */
    OPEN,
    /** Content was removed / suspended. */
    REMOVED,
    /** Creator was sanctioned (e.g. suspended). */
    SANCTIONED,
    /** Report was valid but no action needed. */
    CLOSED,
    /** Report was invalid or abusive. */
    DISMISSED
}
