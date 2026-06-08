package org.earnlumens.mediastore.domain.media.model;

/**
 * Immutable proof backing a rating. This is the anti-fraud spine of the
 * rating system: every rating records <em>why</em> the rater was allowed to
 * rate, captured at creation time and never weakened afterwards.
 *
 * <p>The proof survives the public&harr;paid transition of an entry:
 * a past buyer keeps a {@code PURCHASE} proof even if the entry later becomes
 * free, and free-era ratings stay {@code FREE_VIEW} even if the entry later
 * becomes paid — so a creator cannot inflate a paid entry with ratings
 * collected while it was free.</p>
 */
public enum RatingProofType {

    /**
     * Backed by a verified, on-chain purchase: the rater holds (or held) an
     * ACTIVE entitlement for the entry directly, or for a collection that
     * contains it. Cannot be faked without spending real XLM.
     */
    PURCHASE,

    /**
     * Backed only by authenticated consumption of <em>free</em> content.
     * Weaker than {@code PURCHASE}; kept segregated in the aggregate so it
     * can never dominate the verified-buyer score.
     */
    FREE_VIEW;

    /** Returns the stronger of two proof types ({@code PURCHASE} wins). */
    public static RatingProofType strongest(RatingProofType a, RatingProofType b) {
        if (a == PURCHASE || b == PURCHASE) return PURCHASE;
        return FREE_VIEW;
    }
}
