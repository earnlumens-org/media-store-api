package org.earnlumens.mediastore.domain.media.model;

/**
 * Dispute lifecycle (DATA-MODEL.md §7e / §8.8). Opening is automatic;
 * <b>every</b> transition out of {@link #OPEN} is an explicit administrator
 * action (D-09) — no code path in media-store-api performs one.
 */
public enum DisputeStatus {
    OPEN,
    REFUNDED,
    RESOLVED,
    DISMISSED
}
