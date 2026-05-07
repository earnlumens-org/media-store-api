package org.earnlumens.mediastore.domain.space;

/**
 * Lifecycle of a {@link Space}. Mirrors {@code admin-api}'s
 * {@code SpaceStatus} enum so the underlying Mongo document is parseable
 * from either side without conversion.
 *
 * <p><b>ACTIVE</b> — space is open for publishing and discovery.
 * <p><b>ARCHIVED</b> — soft-deleted; existing entries that already reference
 * it remain reachable, but no new publications are allowed.
 */
public enum SpaceStatus {
    ACTIVE,
    ARCHIVED
}
