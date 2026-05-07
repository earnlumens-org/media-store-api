package org.earnlumens.mediastore.domain.space;

/**
 * Eligibility predicate evaluated when a creator attempts to publish to a
 * {@link Space}. Mirrors {@code admin-api}'s {@code SpacePublishRule}.
 *
 * <ul>
 *   <li><b>ALL</b> — anyone with a valid account on the tenant.</li>
 *   <li><b>VERIFIED_BLUE</b> — only users holding an active blue
 *       credential on this tenant.</li>
 *   <li><b>VERIFIED_GOLD</b> — reserved for a future credential tier.
 *       Currently treated as a placeholder by validation.</li>
 * </ul>
 */
public enum SpacePublishRule {
    ALL,
    VERIFIED_BLUE,
    VERIFIED_GOLD
}
